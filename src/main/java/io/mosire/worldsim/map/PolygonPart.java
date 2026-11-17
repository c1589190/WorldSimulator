package io.mosire.worldsim.map;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 一个带洞多边形。 */
public record PolygonPart(Ring outer, List<Ring> holes) {
  public PolygonPart {
    Objects.requireNonNull(outer, "outer");
    holes = List.copyOf(Objects.requireNonNull(holes, "holes"));
    for (Ring hole : holes) {
      Objects.requireNonNull(hole, "hole");
    }
  }

  public static PolygonPart of(Ring outer) {
    return new PolygonPart(outer, List.of());
  }

  public PolygonPart withHoles(List<Ring> newHoles) {
    return new PolygonPart(outer, newHoles);
  }

  public List<Ring> allRings() {
    var result = new java.util.ArrayList<Ring>(holes.size() + 1);
    result.add(outer);
    result.addAll(holes);
    return Collections.unmodifiableList(result);
  }
}
