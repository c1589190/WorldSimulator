package io.mosire.worldsim.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一个多边形环（不要求调用方重复首点）。
 *
 * <p>规范化：若最后一点与首点相同，去掉重复闭合点；至少保留 3 个点。
 * 真正做拓扑校验时再按 JTS/GeoJSON 规则闭合。
 */
public record Ring(List<Vec2> points) {
  public Ring {
    Objects.requireNonNull(points, "points");
    if (points.size() < 3) {
      throw new IllegalArgumentException("Ring 至少需要 3 个点: " + points.size());
    }
    List<Vec2> copy = new ArrayList<>(points.size());
    for (Vec2 point : points) {
      copy.add(Objects.requireNonNull(point, "Ring 点"));
    }
    if (copy.size() > 1 && copy.get(0).equals(copy.get(copy.size() - 1))) {
      copy.remove(copy.size() - 1);
    }
    if (copy.size() < 3) {
      throw new IllegalArgumentException("Ring 去掉闭合点后至少需要 3 个不同位置的点");
    }
    points = Collections.unmodifiableList(copy);
  }

  /** JTS/GeoJSON 需要的闭合点列表。 */
  public List<Vec2> closedPoints() {
    List<Vec2> closed = new ArrayList<>(points.size() + 1);
    closed.addAll(points);
    closed.add(points.get(0));
    return Collections.unmodifiableList(closed);
  }
}
