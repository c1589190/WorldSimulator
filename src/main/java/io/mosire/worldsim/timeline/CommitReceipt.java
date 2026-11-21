package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.NodeRef;

import java.util.List;

/** Idempotency evidence committed with a timeline node. */
public record CommitReceipt(String actor, String requestId, String requestHash, NodeRef node, List<NodeRef> nodes) {
  public CommitReceipt(String actor, String requestId, String requestHash, NodeRef node) {
    this(actor, requestId, requestHash, node, null);
  }

  public CommitReceipt {
    if (actor == null || actor.isBlank() || requestId == null || requestId.isBlank()
        || requestHash == null || requestHash.isBlank() || node == null) {
      throw new IllegalArgumentException("提交回执的 actor/requestId/requestHash/node 不能为空");
    }
    if (nodes != null) {
      nodes = List.copyOf(nodes);
      if (nodes.isEmpty() || !node.equals(nodes.getLast())) throw new IllegalArgumentException("提交回执缺少末节点");
    }
  }
}
