/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.protocol;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSUtil;

import java.io.IOException;
import java.net.URI;

/**
 * Interface that represents the over the wire information
 * including block locations for a file.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class HdfsLocatedFileStatus extends HdfsFileStatus {
  private LocatedBlocks locations;
  
  /**
   * Constructor
   *  @param length
   *     size
   * @param isdir
   *     if this is directory
   * @param block_replication
 *     the file's replication factor
   * @param blocksize
*     the file's block size
   * @param modification_time
*     most recent modification time
   * @param access_time
*     most recent access time
   * @param permission
*     permission
   * @param owner
*     owner
   * @param group
*     group
   * @param symlink
*     symbolic link
   * @param path
*     local path name in java UTF8 format
   * @param locations
   */
  public HdfsLocatedFileStatus(long length, boolean isdir,
      int block_replication, long blocksize, long modification_time,
      long access_time, FsPermission permission, String owner, String group,
      byte[] symlink, byte[] path, long fileId, LocatedBlocks locations, boolean isStoredInDB, byte storagePolicy) {
    super(length, isdir, block_replication, blocksize, modification_time,
        access_time, permission, owner, group, symlink, path, fileId, isStoredInDB, storagePolicy);
    this.locations = locations;
  }

  public LocatedBlocks getBlockLocations() {
    return locations;
  }

  /**
   * This function is used to transform the underlying HDFS LocatedBlocks to
   * BlockLocations.
   *
   * The returned BlockLocation will have different formats for replicated
   * and erasure coded file.
   * Please refer to
   * {@link org.apache.hadoop.fs.FileSystem#getFileBlockLocations
   * (FileStatus, long, long)}
   * for examples.
   */
  public final LocatedFileStatus makeQualifiedLocated(URI defaultUri, Path path)
    throws IOException {
    makeQualified(defaultUri, path);
    return new LocatedFileStatus(this,
        DFSUtil.locatedBlocks2Locations(getBlockLocations()));
  }

}
