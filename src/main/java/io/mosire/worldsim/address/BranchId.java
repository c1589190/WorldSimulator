package io.mosire.worldsim.address;

import java.util.Objects;

/** 时间线分支身份。只做语法校验，不解释业务含义。 */
public record BranchId(String value) implements Comparable<BranchId> {
  public BranchId {
    Objects.requireNonNull(value, "branch value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("BranchId.value 不得为空白");
    }
    if (value.chars().anyMatch(NodeAddress::isAddressWhitespace)) {
      throw new IllegalArgumentException("BranchId.value 不得含空白: " + value);
    }
    if (value.indexOf(':') >= 0) {
      throw new IllegalArgumentException("BranchId.value 不得含 ':'（旧 namespace 地址不是节点分支）: " + value);
    }
    if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("BranchId.value 不得含 '/' 或 '\\'（分支名必须能作为地址首段）: " + value);
    }
  }

  @Override
  public int compareTo(BranchId other) {
    return value.compareTo(other.value);
  }
}
