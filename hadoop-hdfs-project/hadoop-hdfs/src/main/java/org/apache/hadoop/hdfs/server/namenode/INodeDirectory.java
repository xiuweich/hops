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
package org.apache.hadoop.hdfs.server.namenode;

import com.google.common.annotations.VisibleForTesting;
import io.hops.common.IDsGeneratorFactory;
import io.hops.exception.StorageException;
import io.hops.exception.TransactionContextException;
import io.hops.metadata.hdfs.entity.INodeIdentifier;
import io.hops.metadata.hdfs.entity.MetadataLogEntry;
import io.hops.transaction.EntityManager;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.UnresolvedPathException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.fs.PathIsNotDirectoryException;

/**
 * Directory INode class.
 */
public class INodeDirectory extends INode {
  /**
   * Cast INode to INodeDirectory.
   */
  public static INodeDirectory valueOf(INode inode, Object path
      ) throws FileNotFoundException, PathIsNotDirectoryException {
    if (inode == null) {
      throw new FileNotFoundException("Directory does not exist: "
          + DFSUtil.path2String(path));
    }
    if (!inode.isDirectory()) {
      throw new PathIsNotDirectoryException(DFSUtil.path2String(path));
    }
    return (INodeDirectory) inode;
  }

  protected static final int DEFAULT_FILES_PER_DIRECTORY = 5;
  public final static String ROOT_NAME = "";

  public static final int ROOT_DIR_PARTITION_KEY = ROOT_PARENT_ID;
  public static final short ROOT_DIR_DEPTH =0;

  private boolean metaEnabled;

  public INodeDirectory(int id, String name, PermissionStatus permissions)
      throws IOException {
    super(id, name, permissions);
  }
  
  public INodeDirectory(int id, String name, PermissionStatus permissions, boolean inTree)
      throws IOException {
    super(id, name, permissions, inTree);
  }

  public INodeDirectory(int id, PermissionStatus permissions, long mTime)
      throws IOException {
    super(id, permissions, mTime, 0);
  }

  /**
   * constructor
   */
  INodeDirectory(int id, byte[] name, PermissionStatus permissions, long mtime)
      throws IOException {
    super(id, name, permissions, null, mtime, 0L);
  }
  
  /**
   * copy constructor
   *
   * @param other
   */
  INodeDirectory(INodeDirectory other)
      throws IOException {
    super(other);
    //HOP: FIXME: Mahmoud: the new directory has the same id as the "other"
    // directory so we don't need to notify the children of the directory change
  }
  
  /**
   * @return true unconditionally.
   */
  @Override
  public final boolean isDirectory() {
    return true;
  }

  public boolean isMetaEnabled() {
    return metaEnabled;
  }

  public void setMetaEnabled(boolean metaEnabled) {
    this.metaEnabled = metaEnabled;
  }

  INode removeChild(INode node)
      throws StorageException, TransactionContextException {
    INode existingInode = getChildINode(node.getLocalNameBytes());
    if (existingInode != null) {
      remove(existingInode);
      return existingInode;
    }
    return null;
  }

  /**
   * Replace a child that has the same name as newChild by newChild.
   *
   * @param newChild
   *     Child node to be added
   */
  void replaceChild(INode newChild)
      throws StorageException, TransactionContextException {
    //HOP: Mahmoud: equals based on the inode name
    INode existingINode = getChildINode(newChild.getLocalNameBytes());
    if (existingINode == null) {
      throw new IllegalArgumentException("No child exists to be replaced");
    } else {
      //[M] make sure that the newChild has the same parentid
      if (existingINode.getParentId() != newChild.getParentId()) {
        throw new IllegalArgumentException("Invalid parentid");
      }
      short depth = myDepth();
      int childPartitionKey  = INode.calculatePartitionId(getId(), newChild.getLocalName(), (short) (myDepth()+1));
      newChild.setPartitionId(childPartitionKey);
      EntityManager.update(newChild);
    }
  }
  
  INode getChild(String name)
      throws StorageException, TransactionContextException {
    return getChildINode(DFSUtil.string2Bytes(name));
  }

  private INode getChildINode(byte[] name)
      throws StorageException, TransactionContextException {
    short myDepth = myDepth();
    int childPartitionId = INode.calculatePartitionId(getId(), DFSUtil.bytes2String(name), (short)(myDepth+1));
    INode existingInode = EntityManager
        .find(Finder.ByNameParentIdAndPartitionId, DFSUtil.bytes2String(name),
            getId(), childPartitionId);
    if (existingInode != null && existingInode.isInTree()) {
      return existingInode;
    }
    return null;
  }

  /**
   * @return the INode of the last component in components, or null if the last
   * component does not exist.
   */
  private INode getNode(byte[][] components, boolean resolveLink)
      throws UnresolvedLinkException, StorageException,
      TransactionContextException {
    INodesInPath inodesInPath = getExistingPathINodes(components, 1,
        resolveLink);
    return inodesInPath.inodes[0];
  }

  /**
   * This is the external interface
   */
  INode getNode(String path, boolean resolveLink)
      throws UnresolvedLinkException, StorageException,
      TransactionContextException {
    return getNode(getPathComponents(path), resolveLink);
  }

  /**
   * Retrieve existing INodes from a path. If existing is big enough to store
   * all path components (existing and non-existing), then existing INodes
   * will be stored starting from the root INode into existing[0]; if
   * existing is not big enough to store all path components, then only the
   * last existing and non existing INodes will be stored so that
   * existing[existing.length-1] refers to the INode of the final component.
   * <p/>
   * An UnresolvedPathException is always thrown when an intermediate path
   * component refers to a symbolic link. If the final path component refers
   * to a symbolic link then an UnresolvedPathException is only thrown if
   * resolveLink is true.
   * <p/>
   * <p/>
   * Example: <br>
   * Given the path /c1/c2/c3 where only /c1/c2 exists, resulting in the
   * following path components: ["","c1","c2","c3"],
   * <p/>
   * <p/>
   * <code>getExistingPathINodes(["","c1","c2"], [?])</code> should fill the
   * array with [c2] <br>
   * <code>getExistingPathINodes(["","c1","c2","c3"], [?])</code> should fill
   * the
   * array with [null]
   * <p/>
   * <p/>
   * <code>getExistingPathINodes(["","c1","c2"], [?,?])</code> should fill the
   * array with [c1,c2] <br>
   * <code>getExistingPathINodes(["","c1","c2","c3"], [?,?])</code> should fill
   * the array with [c2,null]
   * <p/>
   * <p/>
   * <code>getExistingPathINodes(["","c1","c2"], [?,?,?,?])</code> should fill
   * the array with [rootINode,c1,c2,null], <br>
   * <code>getExistingPathINodes(["","c1","c2","c3"], [?,?,?,?])</code> should
   * fill the array with [rootINode,c1,c2,null]
   *
   * @param components
   *     array of path component name
   * @param existing
   *     array to fill with existing INodes
   * @param resolveLink
   *     indicates whether UnresolvedLinkException should
   *     be thrown when the path refers to a symbolic link.
   * @return number of existing INodes in the path
   */
  INodesInPath getExistingPathINodes(byte[][] components, int numOfINodes,
      boolean resolveLink) throws UnresolvedLinkException, StorageException,
      TransactionContextException {
    assert
        this.compareTo(components[0]) == 0 :
        "Incorrect name " + getLocalName() + " expected " +
            (components[0] == null ? null :
                DFSUtil.bytes2String(components[0]));

    INodesInPath existing = new INodesInPath(numOfINodes);
    INode curNode = this;
    int count = 0;
    int index = numOfINodes - components.length;
    if (index > 0) {
      index = 0;
    }
    while (count < components.length && curNode != null) {
      final boolean lastComp = (count == components.length - 1);
      if (index >= 0) {
        existing.inodes[index] = curNode;
      }
      if (curNode.isSymlink() && (!lastComp || (lastComp && resolveLink))) {
        final String path = constructPath(components, 0, components.length);
        final String preceding = constructPath(components, 0, count);
        final String remainder =
            constructPath(components, count + 1, components.length);
        final String link = DFSUtil.bytes2String(components[count]);
        final String target = ((INodeSymlink) curNode).getSymlinkString();
        if (NameNode.stateChangeLog.isDebugEnabled()) {
          NameNode.stateChangeLog.debug("UnresolvedPathException " +
              " path: " + path + " preceding: " + preceding +
              " count: " + count + " link: " + link + " target: " + target +
              " remainder: " + remainder);
        }
        throw new UnresolvedPathException(path, preceding, remainder, target);
      }
      count++;
      existing.count = count;
      index++;
      if (lastComp || !curNode.isDirectory()) {
        break;
      }
      INodeDirectory parentDir = (INodeDirectory) curNode;
      curNode = parentDir.getChildINode(components[count]);
    }
    return existing;
  }

  /**
   * Retrieve the existing INodes along the given path. The first INode
   * always exist and is this INode.
   *
   * @param path
   *     the path to explore
   * @param resolveLink
   *     indicates whether UnresolvedLinkException should
   *     be thrown when the path refers to a symbolic link.
   * @return INodes array containing the existing INodes in the order they
   * appear when following the path from the root INode to the
   * deepest INodes. The array size will be the number of expected
   * components in the path, and non existing components will be
   * filled with null
   */
  INodesInPath getExistingPathINodes(String path, boolean resolveLink)
      throws UnresolvedLinkException, StorageException,
      TransactionContextException {
    byte[][] components = getPathComponents(path);
    return getExistingPathINodes(components, components.length, resolveLink);
  }

  /**
   * Given a child's name, return the index of the next child
   *
   * @param name
   *     a child's name
   * @return the index of the next child
   */
  int nextChild(byte[] name)
      throws StorageException, TransactionContextException {
    if (name.length == 0) { // empty name
      return 0;
    }
    int nextPos = Collections.binarySearch(getChildrenList(), name) + 1;
    if (nextPos >= 0) {
      return nextPos;
    }
    return -nextPos;
  }

  /**
   * Add a child inode to the directory.
   *
   * @param node
   *     INode to insert
   * @param setModTime
   *     set modification time for the parent node
   *     not needed when replaying the addition and
   *     the parent already has the proper mod time
   *  @return false if the child with this name already exists; 
   *         otherwise, return true;
   */
  boolean addChild(final INode node, final boolean setModTime) throws IOException{
    INode existingInode = getChildINode(node.getLocalNameBytes());
    if (existingInode != null) {
      return false;
    }

    if (!node.isInTree()) {
      node.inTree();
      node.setParentNoPersistance(this);
      short childDepth = (short)(myDepth()+1);
      node.setPartitionIdNoPersistance(INode.calculatePartitionId(node.getParentId(), node.getLocalName(), childDepth));
      EntityManager.add(node);
      //add the INodeAttributes if it is Directory with Quota
//      if (this instanceof INodeDirectoryWithQuota) { // [S] I think this is not necessary now. Quota update manager will take care of this
//        ((INodeDirectoryWithQuota) this).persistAttributes();
//      }
    } else {
      node.setParent(this);
    }


    // update modification time of the parent directory
    if (setModTime) {
      setModificationTime(node.getModificationTime());
    }
    if (node.getGroupName() == null) {
      node.setGroup(getGroupName());
    }

    node.logMetadataEvent(MetadataLogEntry.Operation.ADD);

    return true;
  }

  /**
   * Add new INode to the file tree.
   * Find the parent and insert
   *
   * @param path
   *     file path
   * @param newNode
   *     INode to be added
   * @return false if the node already exists; otherwise, return true;
   * @throws FileNotFoundException
   *     if parent does not exist or
   * @throws UnresolvedLinkException
   *     if any path component is a symbolic link
   *     is not a directory.
   */
  boolean addINode(String path, INode newNode) throws FileNotFoundException, PathIsNotDirectoryException,
      UnresolvedLinkException, StorageException, IOException {
    byte[][] pathComponents = getPathComponents(path);
    if (pathComponents.length < 2) { // add root
      return false;
    }
    newNode.setLocalName(pathComponents[pathComponents.length - 1]);
    // insert into the parent children list
    INodeDirectory parent = getParent(pathComponents);
    return parent.addChild(newNode, true);
  }


  INodeDirectory getParent(byte[][] pathComponents)
      throws FileNotFoundException, PathIsNotDirectoryException, UnresolvedLinkException, StorageException,
      TransactionContextException{
    if (pathComponents.length < 2)  // add root
    {
      return null;
    }
    // Gets the parent INode
    INodesInPath inodes =  getExistingPathINodes(pathComponents, 2, false);
    return INodeDirectory.valueOf(inodes.inodes[0], pathComponents);
  }

  @Override
  DirCounts spaceConsumedInTree(DirCounts counts)
      throws StorageException, TransactionContextException {
    counts.nsCount += 1;
    if (isInTree()) {
      List<INode> children = getChildren();
      if (children != null) {
        for (INode child : children) {
          child.spaceConsumedInTree(counts);
        }
      }
    }
    return counts;
  }

  @Override
  long[] computeContentSummary(long[] summary)
      throws StorageException, TransactionContextException {
    // Walk through the children of this node, using a new summary array
    // for the (sub)tree rooted at this node
    assert 4 == summary.length;
    long[] subtreeSummary = new long[]{0, 0, 0, 0};
    List<INode> children = getChildren();
    if (children != null) {
      for (INode child : children) {
        child.computeContentSummary(subtreeSummary);
      }
    }
    if (this instanceof INodeDirectoryWithQuota) {
      // Warn if the cached and computed diskspace values differ
      INodeDirectoryWithQuota node = (INodeDirectoryWithQuota) this;
      long space = node.diskspaceConsumed();
      if (-1 != node.getDsQuota() && space != subtreeSummary[3]) {
        NameNode.LOG.warn(
            "Inconsistent diskspace for directory " + getLocalName() +
                ". Cached: " + space + " Computed: " + subtreeSummary[3]);
      }
    }

    // update the passed summary array with the values for this node's subtree
    for (int i = 0; i < summary.length; i++) {
      summary[i] += subtreeSummary[i];
    }

    summary[2]++;
    return summary;
  }

  /**
   * @return an empty list if the children list is null;
   * otherwise, return the children list.
   * The returned list should not be modified.
   */
  public List<INode> getChildrenList()
      throws StorageException, TransactionContextException {
    List<INode> children = getChildren();
    return children == null ? EMPTY_LIST : children;
  }

  /**
   * @return the children list which is possibly null.
   */
  private List<INode> getChildren()
      throws StorageException, TransactionContextException {
    if (!isInTree()) {
      return null;
    }

    short childrenDepth = ((short)(myDepth()+1));
    if(INode.isTreeLevelRandomPartitioned(childrenDepth)){
       return (List<INode>) EntityManager
        .findList(INode.Finder.ByParentIdFTIS, getId());
    }else{
      return (List<INode>) EntityManager
        .findList(Finder.ByParentIdAndPartitionId, getId(), getId()/*partition id for all the childred is the parent id*/);
    }
  }

  @Override
  int collectSubtreeBlocksAndClear(BlocksMapUpdateInfo info)
      throws StorageException, TransactionContextException {
    int total = 1;
    List<INode> children = getChildren();
    if (children == null) {
      return total;
    }
    for (INode child : children) {
      total += child.collectSubtreeBlocksAndClear(info);
    }
    
    parent = null;

    for (INode child : children) {
      remove(child);
    }
    remove(this);

    return total;
  }

  public static int getRootDirPartitionKey(){
    return INode.calculatePartitionId(ROOT_PARENT_ID,ROOT_NAME,ROOT_DIR_DEPTH);
  }

  public static INodeIdentifier getRootIdentifier(){
    INodeIdentifier rootINodeIdentifier = new INodeIdentifier(INodeDirectory.ROOT_INODE_ID,INodeDirectory.ROOT_PARENT_ID, INodeDirectory.ROOT_NAME,
        INodeDirectory.getRootDirPartitionKey());
    rootINodeIdentifier.setDepth(INodeDirectory.ROOT_DIR_DEPTH);
    return rootINodeIdentifier;
  }
  
  /*
   * The following code is to dump the tree recursively for testing.
   *
   * \- foo (INodeDirectory@33dd2717)
   * \- sub1 (INodeDirectory@442172)
   * +- file1 (INodeFile@78392d4)
   * +- file2 (INodeFile@78392d5)
   * +- sub11 (INodeDirectory@8400cff)
   * \- file3 (INodeFile@78392d6)
   * \- z_file4 (INodeFile@45848712)
   */
  static final String DUMPTREE_EXCEPT_LAST_ITEM = "+-";
  static final String DUMPTREE_LAST_ITEM = "\\-";

  @VisibleForTesting
  @Override
  public void dumpTreeRecursively(PrintWriter out, StringBuilder prefix) throws StorageException,
      TransactionContextException {
    super.dumpTreeRecursively(out, prefix);
    if (prefix.length() >= 2) {
      prefix.setLength(prefix.length() - 2);
      prefix.append("  ");
    }
    dumpTreeRecursively(out, prefix, getChildren());
  }

  /**
   * Dump the given subtrees.
   *
   * @param prefix The prefix string that each line should print.
   * @param subs The subtrees.
   */
  @VisibleForTesting
  protected static void dumpTreeRecursively(PrintWriter out,
      StringBuilder prefix, List<? extends INode> subs) throws StorageException, TransactionContextException {
    prefix.append(DUMPTREE_EXCEPT_LAST_ITEM);
    if (subs != null && subs.size() != 0) {
      int i = 0;
      for (; i < subs.size() - 1; i++) {
        subs.get(i).dumpTreeRecursively(out, prefix);
        prefix.setLength(prefix.length() - 2);
        prefix.append(DUMPTREE_EXCEPT_LAST_ITEM);
      }

      prefix.setLength(prefix.length() - 2);
      prefix.append(DUMPTREE_LAST_ITEM);
      subs.get(i).dumpTreeRecursively(out, prefix);
    }
    prefix.setLength(prefix.length() - 2);
  }
  
  /**
   * Used by
   * {@link INodeDirectory#getExistingPathINodes(byte[][], int, boolean)}.
   * Containing INodes information resolved from a given path.
   */
  static class INodesInPath {
    private INode[] inodes;
    private int count;
    
    public INodesInPath(int number) {
      assert (number >= 0);
      this.inodes = new INode[number];
    }
    
    INode[] getINodes() {
      return inodes;
    }
    
    void setINode(int i, INode inode) {
      inodes[i] = inode;
    }
     
    int getCount() {
      return count;
    }
  }
  
  @Override
  public INode cloneInode () throws IOException{
    return new INodeDirectory(this);
  }  
}
