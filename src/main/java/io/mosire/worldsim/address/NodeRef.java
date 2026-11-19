package io.mosire.worldsim.address;

import java.util.Objects;

/** 时间线节点坐标：分支 + 非负 tick。 */
public record NodeRef(BranchId branch, long tick) implements Comparable<NodeRef> {
  public NodeRef {
    Objects.requireNonNull(branch, "branch");
    if (tick < 0) {
      throw new IllegalArgumentException("tick 必须 >= 0: " + tick);
    }
    if (branch.value().length() + 1 + Long.toString(tick).length() > NodeAddress.MAX_LENGTH) {
      throw new IllegalArgumentException("节点坐标超过地址长度上限: " + NodeAddress.MAX_LENGTH);
    }
  }

  public String label() {
    return branch.value() + "-" + tick;
  }

  @Override
  public int compareTo(NodeRef other) {
    int byBranch = branch.compareTo(other.branch);
    return byBranch != 0 ? byBranch : Long.compare(tick, other.tick);
  }

  @Override
  public String toString() {
    return label();
  }
}
