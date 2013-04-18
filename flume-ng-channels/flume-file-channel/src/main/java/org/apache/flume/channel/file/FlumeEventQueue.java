/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.channel.file;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Queue of events in the channel. This queue stores only
 * {@link FlumeEventPointer} objects which are represented
 * as 8 byte longs internally. Additionally the queue itself
 * of longs is stored as a memory mapped file with a fixed
 * header and circular queue semantics. The header of the queue
 * contains the timestamp of last sync, the queue size and
 * the head position.
 */
class FlumeEventQueue {
  private static final Logger LOG = LoggerFactory
  .getLogger(FlumeEventQueue.class);
  private static final long VERSION = 2;
  private static final int EMPTY = 0;
  private static final int INDEX_VERSION = 0;
  private static final int INDEX_WRITE_ORDER_ID = 1;
  private static final int INDEX_SIZE = 2;
  private static final int INDEX_HEAD = 3;
  private static final int INDEX_CHECKPOINT_MARKER = 4;
  private static final int CHECKPOINT_COMPLETE = EMPTY;
  private static final int CHECKPOINT_INCOMPLETE = 1;
  private static final int INDEX_ACTIVE_LOG = 5;
  private static final int MAX_ACTIVE_LOGS = 1024;
  private static final int HEADER_SIZE = 1029;
  private static final int MAX_ALLOC_BUFFER_SIZE = 2*1024*1024; // 2MB
  private final Map<Integer, AtomicInteger> fileIDCounts = Maps.newHashMap();
  private final MappedByteBuffer mappedBuffer;
  private final LongBuffer elementsBuffer;
  private LongBufferWrapper elements;
  private final RandomAccessFile checkpointFile;
  private final java.nio.channels.FileChannel checkpointFileHandle;
  private final int queueCapacity;
  private final String channelNameDescriptor;

  private int queueSize;
  private int queueHead;
  private long logWriteOrderID;

  /**
   * @param capacity max event capacity of queue
   * @throws IOException
   */
  FlumeEventQueue(int capacity, File file, String name) throws IOException {
    Preconditions.checkArgument(capacity > 0,
        "Capacity must be greater than zero");
    this.channelNameDescriptor = "[channel=" + name + "]";
    this.queueCapacity = capacity;

    if (!file.exists()) {
      Preconditions.checkState(file.createNewFile(), "Unable to create file: "
          + file.getCanonicalPath() + " " + channelNameDescriptor);
    }

    boolean freshlyAllocated = false;
    checkpointFile = new RandomAccessFile(file, "rw");
    if (checkpointFile.length() == 0) {
      // Allocate
      LOG.info("Event queue has zero allocation. Initializing to capacity. "
          + "Please wait...");
      int totalBytes = (capacity + HEADER_SIZE)*8;
      if (totalBytes <= MAX_ALLOC_BUFFER_SIZE) {
        checkpointFile.write(new byte[totalBytes]);
      } else {
        byte[] initBuffer = new byte[MAX_ALLOC_BUFFER_SIZE];
        int remainingBytes = totalBytes;
        while (remainingBytes >= MAX_ALLOC_BUFFER_SIZE) {
          checkpointFile.write(initBuffer);
          remainingBytes -= MAX_ALLOC_BUFFER_SIZE;
        }
        if (remainingBytes > 0) {
          checkpointFile.write(initBuffer, 0, remainingBytes);
        }
      }

      LOG.info("Event queue allocation complete");
      freshlyAllocated = true;
    } else {
      int fileCapacity = (int) checkpointFile.length() / 8;
      int expectedCapacity = capacity + HEADER_SIZE;

      Preconditions.checkState(fileCapacity == expectedCapacity,
          "Capacity cannot be changed once the channel is initialized "
              + channelNameDescriptor + ": fileCapacity = " + fileCapacity
              + ", expectedCapacity = " + expectedCapacity);
    }

    checkpointFileHandle = checkpointFile.getChannel();

    mappedBuffer = checkpointFileHandle.map(MapMode.READ_WRITE, 0,
        file.length());

    elementsBuffer = mappedBuffer.asLongBuffer();
    if (freshlyAllocated) {
      elementsBuffer.put(INDEX_VERSION, VERSION);
    } else {
      int version = (int) elementsBuffer.get(INDEX_VERSION);
      Preconditions.checkState(version == VERSION,
          "Invalid version: " + version + channelNameDescriptor);
      logWriteOrderID = elementsBuffer.get(INDEX_WRITE_ORDER_ID);
      queueSize = (int) elementsBuffer.get(INDEX_SIZE);
      queueHead = (int) elementsBuffer.get(INDEX_HEAD);

      long checkpointComplete =
          (int) elementsBuffer.get(INDEX_CHECKPOINT_MARKER);
      Preconditions.checkState(checkpointComplete == CHECKPOINT_COMPLETE,
          "The last checkpoint was not completed correctly. Please delete "
          + "the checkpoint file: " + file.getCanonicalPath() + " to rebuild "
          + "the checkpoint and start again. " + channelNameDescriptor);

      int indexMaxLog = INDEX_ACTIVE_LOG + MAX_ACTIVE_LOGS;
      for (int i = INDEX_ACTIVE_LOG; i < indexMaxLog; i++) {
        long nextFileCode = elementsBuffer.get(i);
        if (nextFileCode  != EMPTY) {
          Pair<Integer, Integer> idAndCount =
              deocodeActiveLogCounter(nextFileCode);
          fileIDCounts.put(idAndCount.getLeft(),
              new AtomicInteger(idAndCount.getRight()));
        }
      }
    }

    elements = new LongBufferWrapper(elementsBuffer, channelNameDescriptor);
  }

  private Pair<Integer, Integer> deocodeActiveLogCounter(long value) {
    int fileId = (int) (value >>> 32);
    int count = (int) value;

    return Pair.of(fileId, count);
  }

  private long encodeActiveLogCounter(int fileId, int count) {
    long result = fileId;
    result = (long)fileId << 32;
    result += (long) count;
    return result;
  }

  synchronized long getLogWriteOrderID() {
    return logWriteOrderID;
  }

  synchronized boolean checkpoint(boolean force) {
    if (!elements.syncRequired() && !force) {
      LOG.debug("Checkpoint not required");
      return false;
    }

    // Start checkpoint
    elementsBuffer.put(INDEX_CHECKPOINT_MARKER, CHECKPOINT_INCOMPLETE);

    updateHeaders();

    List<Long> fileIdAndCountEncoded = new ArrayList<Long>();
    for (Integer fileId : fileIDCounts.keySet()) {
      Integer count = fileIDCounts.get(fileId).get();
      long value = encodeActiveLogCounter(fileId, count);
      fileIdAndCountEncoded.add(value);
    }

    int emptySlots = MAX_ACTIVE_LOGS - fileIdAndCountEncoded.size();
    for (int i = 0; i < emptySlots; i++)  {
      fileIdAndCountEncoded.add(0L);
    }
    for (int i = 0; i < MAX_ACTIVE_LOGS; i++) {
      elementsBuffer.put(i + INDEX_ACTIVE_LOG, fileIdAndCountEncoded.get(i));
    }

    elements.sync();

    // Finish checkpoint
    elementsBuffer.put(INDEX_CHECKPOINT_MARKER, CHECKPOINT_COMPLETE);
    mappedBuffer.force();

    return true;
  }

  /**
   * Retrieve and remove the head of the queue.
   *
   * @return FlumeEventPointer or null if queue is empty
   */
  synchronized FlumeEventPointer removeHead() {
    if(queueSize == 0) {
      return null;
    }

    long value = remove(0);
    Preconditions.checkState(value != EMPTY, "Empty value "
          + channelNameDescriptor);

    FlumeEventPointer ptr = FlumeEventPointer.fromLong(value);
    decrementFileID(ptr.getFileID());
    return ptr;
  }

  /**
   * Add a FlumeEventPointer to the head of the queue
   * @param FlumeEventPointer to be added
   * @return true if space was available and pointer was
   * added to the queue
   */
  synchronized boolean addHead(FlumeEventPointer e) {
    if (queueSize == queueCapacity) {
      return false;
    }

    long value = e.toLong();
    Preconditions.checkArgument(value != EMPTY);
    incrementFileID(e.getFileID());

    add(0, value);
    return true;
  }


  /**
   * Add a FlumeEventPointer to the tail of the queue
   * this will normally be used when recovering from a
   * crash
   * @param FlumeEventPointer to be added
   * @return true if space was available and pointer
   * was added to the queue
   */
  synchronized boolean addTail(FlumeEventPointer e) {
    if (queueSize == queueCapacity) {
      return false;
    }

    long value = e.toLong();
    Preconditions.checkArgument(value != EMPTY);
    incrementFileID(e.getFileID());

    add(queueSize, value);
    return true;
  }

  /**
   * Remove FlumeEventPointer from queue, will normally
   * only be used when recovering from a crash
   * @param FlumeEventPointer to be removed
   * @return true if the FlumeEventPointer was found
   * and removed
   */
  synchronized boolean remove(FlumeEventPointer e) {
    long value = e.toLong();
    Preconditions.checkArgument(value != EMPTY);
    for (int i = 0; i < queueSize; i++) {
      if(get(i) == value) {
        remove(i);
        FlumeEventPointer ptr = FlumeEventPointer.fromLong(value);
        decrementFileID(ptr.getFileID());
        return true;
      }
    }
    return false;
  }
  /**
   * @return a copy of the set of fileIDs which are currently on the queue
   * will be normally be used when deciding which data files can
   * be deleted
   */
  synchronized SortedSet<Integer> getFileIDs() {
    //Java implements clone pretty well. The main place this is used
    //in checkpointing and deleting old files, so best
    //to use a sorted set implementation.
    return new TreeSet<Integer>(fileIDCounts.keySet());
  }

  protected void incrementFileID(int fileID) {
    AtomicInteger counter = fileIDCounts.get(fileID);
    if(counter == null) {
      Preconditions.checkState(fileIDCounts.size() < MAX_ACTIVE_LOGS,
          "Too many active logs " + channelNameDescriptor);
      counter = new AtomicInteger(0);
      fileIDCounts.put(fileID, counter);
    }
    counter.incrementAndGet();
  }

  protected void decrementFileID(int fileID) {
    AtomicInteger counter = fileIDCounts.get(fileID);
    Preconditions.checkState(counter != null, "null counter "
        + channelNameDescriptor);
    int count = counter.decrementAndGet();
    if(count == 0) {
      fileIDCounts.remove(fileID);
    }
  }

  protected long get(int index) {
    if (index < 0 || index > queueSize - 1) {
      throw new IndexOutOfBoundsException(String.valueOf(index)
          + channelNameDescriptor);
    }

    return elements.get(getPhysicalIndex(index));
  }

  private void set(int index, long value) {
    if (index < 0 || index > queueSize - 1) {
      throw new IndexOutOfBoundsException(String.valueOf(index)
          + channelNameDescriptor);
    }

    elements.put(getPhysicalIndex(index), value);
  }

  protected boolean add(int index, long value) {
    if (index < 0 || index > queueSize) {
      throw new IndexOutOfBoundsException(String.valueOf(index)
          + channelNameDescriptor);
    }

    if (queueSize == queueCapacity) {
      return false;
    }

    queueSize++;

    if (index <= queueSize/2) {
      // Shift left
      queueHead--;
      if (queueHead < 0) {
        queueHead = queueCapacity - 1;
      }
      for (int i = 0; i < index; i++) {
        set(i, get(i+1));
      }
    } else {
      // Sift right
      for (int i = queueSize - 1; i > index; i--) {
        set(i, get(i-1));
      }
    }
    set(index, value);
    return true;
  }

  protected synchronized long remove(int index) {
    if (index < 0 || index > queueSize - 1) {
      throw new IndexOutOfBoundsException("index = " + index
          + ", queueSize " + queueSize +" " + channelNameDescriptor);
    }
    long value = get(index);

    if (index > queueSize/2) {
      // Move tail part to left
      for (int i = index; i < queueSize - 1; i++) {
        long rightValue = get(i+1);
        set(i, rightValue);
      }
      set(queueSize - 1, EMPTY);
    } else {
      // Move head part to right
      for (int i = index - 1; i >= 0; i--) {
        long leftValue = get(i);
        set(i+1, leftValue);
      }
      set(0, EMPTY);
      queueHead++;
      if (queueHead == queueCapacity) {
        queueHead = 0;
      }
    }

    queueSize--;
    return value;
  }

  private synchronized void updateHeaders() {
    logWriteOrderID = WriteOrderOracle.next();
    elementsBuffer.put(INDEX_WRITE_ORDER_ID, logWriteOrderID);
    elementsBuffer.put(INDEX_SIZE, queueSize);
    elementsBuffer.put(INDEX_HEAD, queueHead);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Updating checkpoint headers: ts: " + logWriteOrderID + ", queueSize: "
          + queueSize + ", queueHead: " + queueHead + " " + channelNameDescriptor);
    }
  }


  private int getPhysicalIndex(int index) {
    return HEADER_SIZE + (queueHead + index) % queueCapacity;
  }

  protected synchronized int getSize() {
    return queueSize;
  }

  /**
   * @return max capacity of the queue
   */
  public int getCapacity() {
    return queueCapacity;
  }

  static class LongBufferWrapper {
    private final LongBuffer buffer;
    private final String channelNameDescriptor;

    Map<Integer, Long> overwriteMap = new HashMap<Integer, Long>();

    LongBufferWrapper(LongBuffer lb, String nameDescriptor) {
      buffer = lb;
      channelNameDescriptor = nameDescriptor;
    }

    long get(int index) {
      long result = EMPTY;
      if (overwriteMap.containsKey(index)) {
        result = overwriteMap.get(index);
      } else {
        result = buffer.get(index);
      }

      return result;
    }

    void put(int index, long value) {
      overwriteMap.put(index, value);
    }

    boolean syncRequired() {
      return overwriteMap.size() > 0;
    }

    void sync() {
      Iterator<Integer> it = overwriteMap.keySet().iterator();
      while (it.hasNext()) {
        int index = it.next();
        long value = overwriteMap.get(index);

        buffer.put(index, value);
        it.remove();
      }

      Preconditions.checkState(overwriteMap.size() == 0,
          "concurrent update detected " + channelNameDescriptor);
    }
  }

  public static void main(String[] args) throws IOException {
    File file = new File(args[0]);
    if(!file.exists()) {
      throw new IOException("File " + file + " does not exist");
    }
    if(file.length() == 0) {
      throw new IOException("File " + file + " is empty");
    }
    int capacity = (int)((file.length() - (HEADER_SIZE * 8L)) / 8L);
    FlumeEventQueue queue = new FlumeEventQueue(capacity, file, "debug");
    System.out.println("File Reference Counts" + queue.fileIDCounts);
    System.out.println("Queue Capacity " + queue.getCapacity());
    System.out.println("Queue Size " + queue.getSize());
    System.out.println("Queue Head " + queue.queueHead);
    for (int index = 0; index < queue.getCapacity(); index++) {
      long value = queue.elements.get(queue.getPhysicalIndex(index));
      int fileID = (int)(value >>> 32);
      int offset = (int)value;
      System.out.println(index + ":" + Long.toHexString(value) + " fileID = "
          + fileID + ", offset = " + offset);
    }
  }
}