package io.mosire.worldsim.map;

import io.mosire.worldsim.state.Evidence;

import java.util.Objects;

/** 无向省邻接。ID 在构造期按字典序规范化，保证同一关系只有一种表示。 */
public record Adjacency(String a, String b, String kind, double borderLength, String through, RiverCrossing riverCrossing) {
  public record RiverCrossing(boolean crosses, String shapeHash, Evidence evidence) {
    public RiverCrossing {
      if (shapeHash == null || !shapeHash.matches("sha256:[a-f0-9]{64}")) {
        throw new IllegalArgumentException("跨河确认需要省形 hash");
      }
      Objects.requireNonNull(evidence, "跨河证据");
      if (evidence.source() == null || evidence.status().equals("unknown") || evidence.status().equals("legacy-ambiguous")) {
        throw new IllegalArgumentException("跨河确认需要有来源的证据");
      }
    }
  }
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
    if (riverCrossing != null && (!kind.equals("land") || borderLength <= 0 || through != null)) {
      throw new IllegalArgumentException("跨河确认须关联普通陆地邻接");
    }
    if (a.compareTo(b) > 0) {
      String tmp = a;
      a = b;
      b = tmp;
    }
  }

  public Adjacency(String a, String b, String kind, double borderLength) {
    this(a, b, kind, borderLength, null, null);
  }

  public Adjacency(String a, String b, String kind, double borderLength, String through) {
    this(a, b, kind, borderLength, through, null);
  }

  public Adjacency withRiverCrossing(RiverCrossing value) {
    return new Adjacency(a, b, kind, borderLength, through, value);
  }

  public boolean connects(String x, String y) {
    return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
  }

  public boolean permitsLandMarch() {
    return kind.equals("land") && borderLength > 0 && through == null;
  }
}
