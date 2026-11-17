package io.mosire.worldsim.map;

import java.util.Objects;

/** 无向省邻接。ID 在构造期按字典序规范化，保证同一关系只有一种表示。 */
public record Adjacency(String a, String b, String kind, double borderLength, String through) {
  public Adjacency {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    Objects.requireNonNull(kind, "kind");
    if (a.isBlank() || b.isBlank()) {
      throw new IllegalArgumentException("Adjacency 两端 id 不得为空白");
    }
    if (a.equals(b)) {
      throw new IllegalArgumentException("Adjacency 不能自环: " + a);
    }
    if (!Double.isFinite(borderLength) || borderLength < 0) {
      throw new IllegalArgumentException("Adjacency.borderLength 必须是非负有限数: " + borderLength);
    }
    if (kind.isBlank()) {
      throw new IllegalArgumentException("Adjacency.kind 不得为空白");
    }
    if (through != null && through.isBlank()) {
      through = null;
    }
    if (a.compareTo(b) > 0) {
      String tmp = a;
      a = b;
      b = tmp;
    }
  }

  public Adjacency(String a, String b, String kind, double borderLength) {
    this(a, b, kind, borderLength, null);
  }

  public boolean connects(String x, String y) {
    return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
  }
}
