package io.mosire.worldsim.map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

/** ProvinceShape 与 JTS Geometry 的纯转换。JTS 只用于校验/拓扑，不是持久化格式。 */
public final class JtsGeometry {
  private static final GeometryFactory FACTORY = new GeometryFactory();

  private JtsGeometry() {}

  public static Geometry toGeometry(ProvinceShape shape) {
    List<Polygon> polygons = new ArrayList<>(shape.parts().size());
    for (PolygonPart part : shape.parts()) {
      polygons.add(toPolygon(part));
    }
    if (polygons.size() == 1) {
      return polygons.get(0);
    }
    return FACTORY.createMultiPolygon(polygons.toArray(Polygon[]::new));
  }

  public static Polygon toPolygon(PolygonPart part) {
    LinearRing shell = ring(part.outer());
    LinearRing[] holes = part.holes().stream().map(JtsGeometry::ring).toArray(LinearRing[]::new);
    return FACTORY.createPolygon(shell, holes);
  }

  public static Point point(Vec2 value) {
    return FACTORY.createPoint(new Coordinate(value.x(), value.y()));
  }

  private static LinearRing ring(Ring ring) {
    List<Vec2> closed = ring.closedPoints();
    Coordinate[] coordinates = new Coordinate[closed.size()];
    for (int i = 0; i < closed.size(); i++) {
      Vec2 point = closed.get(i);
      coordinates[i] = new Coordinate(point.x(), point.y());
    }
    return FACTORY.createLinearRing(coordinates);
  }
}
