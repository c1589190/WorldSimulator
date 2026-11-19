package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.NodeRef;

/** Idempotency evidence committed with a timeline node. */
public record CommitReceipt(String actor, String requestId, String requestHash, NodeRef node) {
  public CommitReceipt {
    if (actor == null || actor.isBlank() || requestId == null || requestId.isBlank()
        || requestHash == null || requestHash.isBlank() || node == null) {
      throw new IllegalArgumentException("提交回执的 actor/requestId/requestHash/node 不能为空");
    }
  }
}
