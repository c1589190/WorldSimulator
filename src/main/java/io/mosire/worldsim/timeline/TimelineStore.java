package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 时间线持久化端口。实现可以是 SQLite，后续也可以是内存/测试替身。
 *
 * <p>边界：本端口只持久化已保存节点与派生 checkpoint，不保存 working copy。
 * 节点一旦写入不可覆盖；分支内新节点必须接在当前 head 之后。
 */
public interface TimelineStore extends AutoCloseable {
  void initialize();

  boolean isEmpty();

  /** 全时间线唯一的根节点；首版只允许一个创世节点。 */
  Optional<TimelineNode> genesis();

  Optional<TimelineNode> find(NodeRef ref);

  boolean exists(NodeRef ref);

  /** 根 -> 目标节点的路径；节点不存在时返回空列表。 */
  List<TimelineNode> pathToRoot(NodeRef ref);

  List<TimelineNode> nodes(BranchId branch);

  Set<BranchId> branches();

  Optional<NodeRef> head(BranchId branch);

  /**
   * 原子保存一个节点；如果 checkpointStateJson 非 null，同时写该节点的检查点。
   * 节点已存在时不得覆盖。父节点必须已保存；同分支父节点必须是 head；节点 tick 必须大于父节点。
   */
  void saveNode(TimelineNode node, String checkpointStateJson);

  /** Commit the idempotency receipt in the same publication as the node. */
  default void saveNode(TimelineNode node, String checkpointStateJson, CommitReceipt receipt) {
    if (receipt != null) {
      throw new TimelineException("TIMELINE_RECEIPT_UNSUPPORTED", "此存储不支持原子提交回执");
    }
    saveNode(node, checkpointStateJson);
  }

  default Optional<CommitReceipt> receipt(String actor, String requestId) {
    return Optional.empty();
  }

  /** 为已保存节点生成/覆盖派生 checkpoint。 */
  void saveCheckpoint(NodeRef ref, String checkpointStateJson);

  Optional<String> checkpoint(NodeRef ref);

  /** Re-read persisted metadata where applicable before a full verification. */
  default void verifyStorageIntegrity() {}

  @Override
  void close();
}
