package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.NodeRef;

import java.util.Objects;

/**
 * 已保存的时间线节点。只有能在 store 中查到的节点才是 saved；working copy 不是节点。
 *
 * @param ref 节点坐标
 * @param parent 父节点；创世为 null
 * @param changeSetJson 相对父节点的增量（规范 JSON）
 * @param stateHash 该节点完整状态的内容哈希
 * @param savedAtEpochMilli 保存时间（审计，不参与世界状态）
 * @param savedBy 保存者
 */
public record TimelineNode(
    NodeRef ref,
    NodeRef parent,
    String changeSetJson,
    String stateHash,
    long savedAtEpochMilli,
    String savedBy) {

  public TimelineNode {
    Objects.requireNonNull(ref, "ref");
    if (changeSetJson == null) {
      throw new IllegalArgumentException("changeSetJson 不得为 null（空增量用 {}）");
    }
    if (stateHash == null || stateHash.isBlank()) {
      throw new IllegalArgumentException("stateHash 不得为空白");
    }
    if (savedBy == null || savedBy.isBlank()) {
      throw new IllegalArgumentException("savedBy 不得为空白");
    }
  }

  public boolean hasParent() {
    return parent != null;
  }
}
