// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.util.List;
import java.util.Map;

import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.LiteralExpr;
import com.cloudera.impala.thrift.ImpalaInternalServiceConstants;
import com.cloudera.impala.thrift.TExpr;
import com.cloudera.impala.thrift.THdfsPartition;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Query-relevant information for one table partition. Not thread safe due to a static
 * counter that's incremented for every time the constructor is called.
 */
public class HdfsPartition {
  /**
   * Metadata for a single file in this partition - the full path and the length of the
   * file.
   */
  static public class FileDescriptor {
    private final String filePath;
    private final long fileLength;
    private HdfsCompression fileCompression;

    public String getFilePath() { return filePath; }
    public long getFileLength() { return fileLength; }
    public HdfsCompression getFileCompression() { return fileCompression; }

    public FileDescriptor(String filePath, long fileLength) {
      Preconditions.checkNotNull(filePath);
      Preconditions.checkArgument(fileLength >= 0);
      this.filePath = filePath;
      this.fileLength = fileLength;
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this).add("Path", filePath)
          .add("Length", fileLength).toString();
    }

    public void setCompression(HdfsCompression compression) {
      fileCompression = compression;
    }
  }

  private final HdfsTable table;
  private final List<LiteralExpr> partitionKeyValues;

  // estimated number of rows in partition; -1: unknown
  private long numRows = -1;

  // partition-specific stats for each column
  // TODO: fill this
  private final Map<Column, ColumnStats> columnStats = Maps.newHashMap();

  private static long partitionIdCounter = 0;

  // A unique ID for each partition, used to identify a partition in the thrift
  // representation of a table.
  private final long id;

  /*
   * Note: Although you can write multiple formats to a single partition (by changing
   * the format before each write), Hive won't let you read that data and neither should
   * we. We should therefore treat mixing formats inside one partition as user error.
   * It's easy to add per-file metadata to FileDescriptor if this changes.
   */
  private final HdfsStorageDescriptor fileFormatDescriptor;

  private final org.apache.hadoop.hive.metastore.api.Partition msPartition;

  private final List<HdfsPartition.FileDescriptor> fileDescriptors;

  public HdfsStorageDescriptor getInputFormatDescriptor() { return fileFormatDescriptor; }

  /**
   * Returns the metastore.api.Partition object this HdfsPartition represents. Returns
   * null if this is the default partition.
   */
  public org.apache.hadoop.hive.metastore.api.Partition getMetaStorePartition() {
    return msPartition;
  }

  public long getId() { return id; }

  public HdfsTable getTable() { return table; }

  public void setNumRows(long numRows) {
    this.numRows = numRows;
  }

  public long getNumRows() { return numRows; }

  /**
   * Returns an immutable list of partition key expressions
   */
  public List<LiteralExpr> getPartitionValues() { return partitionKeyValues; }

  public List<HdfsPartition.FileDescriptor> getFileDescriptors() {
    return fileDescriptors;
  }

  public List<LiteralExpr> getPartitionKeyValues() {
    return partitionKeyValues;
  }

  private HdfsPartition(HdfsTable table,
      org.apache.hadoop.hive.metastore.api.Partition msPartition,
      List<LiteralExpr> partitionKeyValues,
      HdfsStorageDescriptor fileFormatDescriptor,
      List<HdfsPartition.FileDescriptor> fileDescriptors, long id) {
    this.table = table;
    this.msPartition = msPartition;
    this.partitionKeyValues = ImmutableList.copyOf(partitionKeyValues);
    this.fileDescriptors = ImmutableList.copyOf(fileDescriptors);
    this.fileFormatDescriptor = fileFormatDescriptor;
    this.id = id;
  }

  public HdfsPartition(HdfsTable table, 
      org.apache.hadoop.hive.metastore.api.Partition msPartition,
      List<LiteralExpr> partitionKeyValues,
      HdfsStorageDescriptor fileFormatDescriptor,
      List<HdfsPartition.FileDescriptor> fileDescriptors) {
    this(table, msPartition, partitionKeyValues, fileFormatDescriptor, fileDescriptors,
        partitionIdCounter++);
  }

  public static HdfsPartition defaultPartition(
      HdfsTable table, HdfsStorageDescriptor storageDescriptor) {
    List<LiteralExpr> emptyExprList = Lists.newArrayList();
    List<FileDescriptor> emptyFileDescriptorList = Lists.newArrayList();
    HdfsPartition partition = new HdfsPartition(table, null, emptyExprList,
        storageDescriptor, emptyFileDescriptorList,
        ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID);
    return partition;
  }

  /**
   * Return the size (in bytes) of all the files inside this partition
   * @return
   */
  public long getSize() {
    long result = 0;
    for (HdfsPartition.FileDescriptor fileDescriptor: fileDescriptors) {
      result += fileDescriptor.getFileLength();
    }
    return result;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
      .add("fileDescriptors", fileDescriptors)
      .toString();
  }

  public THdfsPartition toThrift() {
    List<TExpr> thriftExprs =
      Expr.treesToThrift(getPartitionValues());

    return new THdfsPartition((byte)fileFormatDescriptor.getLineDelim(),
        (byte)fileFormatDescriptor.getFieldDelim(),
        (byte)fileFormatDescriptor.getCollectionDelim(),
        (byte)fileFormatDescriptor.getMapKeyDelim(),
        (byte)fileFormatDescriptor.getEscapeChar(),
        fileFormatDescriptor.getFileFormat().toThrift(), thriftExprs,
        fileFormatDescriptor.getBlockSize(), fileFormatDescriptor.getCompression());
  }
}
