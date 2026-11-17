package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 时间线持久化端口。实现可以是 SQLite，后续也可以是内存/测试替身。 */
public interface TimelineStore extends AutoCloseable {
  void initialize();

  Optional<TimelineNode> find(NodeRef ref);

  boolean exists(NodeRef ref);

  /** 根 -> 目标节点的路径；节点不存在时返回空列表。 */
  List<TimelineNode> pathToRoot(NodeRef ref);

  List<TimelineNode> nodes(BranchId branch);

  Set<BranchId> branches();

  Optional<NodeRef> head(BranchId branch);

  /**
   * 原子保存一个节点；如果 checkpointStateJson 非 null，同时写该节点的检查点。
   * 节点已存在时不得覆盖。
   */
  void saveNode(TimelineNode node, String checkpointStateJson);

  Optional<String> checkpoint(NodeRef ref);

  @Override
  void close();
}
