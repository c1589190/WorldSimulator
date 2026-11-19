package io.mosire.worldsim.app;

import io.mosire.worldsim.map.Vec2;

import java.util.List;

/** River centerlines supplied by the external reference for a map sheet. */
public record RiverReference(String source, String sourceHash, List<Line> lines) {
  public RiverReference {
    if (source == null || source.isBlank() || sourceHash == null || !sourceHash.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("河流参考需要来源及其 hash");
    }
    lines = List.copyOf(lines);
  }

  public record Line(int width, List<Vec2> points) {
    public Line {
      points = List.copyOf(points);
      if (width < 3 || width > 11 || points.size() < 2) {
        throw new IllegalArgumentException("河流参考需要有效的河宽级和至少两个点");
      }
    }
  }
}
