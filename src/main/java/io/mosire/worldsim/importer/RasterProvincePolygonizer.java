package io.mosire.worldsim.importer;

import io.mosire.worldsim.map.PolygonPart;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Ring;
import io.mosire.worldsim.map.Vec2;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从 HOI4 24-bit provinces.bmp 的 province ID 栅格提取原生 polygon。
 *
 * <p>做法：把每个像素的四条边中与异省相邻的边抽成有向边界边（省内右侧），按省拼成 ring；
 * 共享省界只来自同一组像素边界，转换成 polygon 后仍精确重合。ring 先去掉共线顶点，再按嵌套
 * 关系区分 shell/hole，最后交给 JTS 校验/修复。
 */
final class RasterProvincePolygonizer {
  private static final GeometryFactory FACTORY = new GeometryFactory();
  private static final double AREA_EPSILON = 1e-9;

  private RasterProvincePolygonizer() {}

  static Result polygonize(int width, int height, int[] provinceIds, Set<Integer> usedProvinceIds) {
    if (width < 1 || height < 1) {
      throw new Hoi4ImportException("HOI4_RASTER_INVALID", "栅格尺寸非法: " + width + "x" + height);
    }
    if (provinceIds.length != Math.multiplyExact(width, height)) {
      throw new Hoi4ImportException("HOI4_RASTER_INVALID",
          "栅格像素数组长度不匹配: " + provinceIds.length + " vs " + width + "x" + height);
    }
    Objects.requireNonNull(usedProvinceIds, "usedProvinceIds");
    int vertexWidth = width + 1;
    int vertexHeight = height + 1;

    Map<Integer, LongList> edgesByProvince = new HashMap<>(usedProvinceIds.size() * 2);
    for (int id : usedProvinceIds) {
      edgesByProvince.put(id, new LongList(64));
    }
    Map<Long, Integer> borderLengths = new HashMap<>();

    for (int y = 0; y < height; y++) {
      int row = y * width;
      for (int x = 0; x < width; x++) {
        int index = row + x;
        int id = provinceIds[index];
        LongList edges = edgesByProvince.get(id);
        if (edges == null) {
          continue;
        }
        int topLeft = y * vertexWidth + x;
        int topRight = topLeft + 1;
        int bottomLeft = (y + 1) * vertexWidth + x;
        int bottomRight = bottomLeft + 1;

        if (y == 0 || provinceIds[index - width] != id) {
          edges.add(packEdge(topLeft, topRight));
        }
        if (x == width - 1 || provinceIds[index + 1] != id) {
          edges.add(packEdge(topRight, bottomRight));
        }
        if (y == height - 1 || provinceIds[index + width] != id) {
          edges.add(packEdge(bottomRight, bottomLeft));
        }
        if (x == 0 || provinceIds[index - 1] != id) {
          edges.add(packEdge(bottomLeft, topLeft));
        }

        if (y > 0) {
          int other = provinceIds[index - width];
          if (other != id) {
            addBorder(borderLengths, id, other);
          }
        }
        if (x > 0) {
          int other = provinceIds[index - 1];
          if (other != id) {
            addBorder(borderLengths, id, other);
          }
        }
      }
    }

    Map<Integer, ProvinceShape> shapes = new LinkedHashMap<>();
    Map<Integer, Vec2> displayPoints = new LinkedHashMap<>();
    int ringsTraced = 0;
    int repaired = 0;
    List<Integer> sortedIds = new ArrayList<>(new TreeSet<>(usedProvinceIds));
    for (int id : sortedIds) {
      LongList edges = edgesByProvince.get(id);
      if (edges == null || edges.size() == 0) {
        throw new Hoi4ImportException("HOI4_POLYGON_TRACE_FAILED",
            "省 " + id + " 没有边界边");
      }
      List<Ring> rings = traceRings(edges, vertexWidth);
      if (rings.isEmpty()) {
        throw new Hoi4ImportException("HOI4_POLYGON_TRACE_FAILED",
            "省 " + id + " 没提取到闭合 ring");
      }
      ringsTraced += rings.size();
      ShapeResult shape = ringsToShape(id, rings);
      shapes.put(id, shape.shape());
      displayPoints.put(id, shape.displayPoint());
      repaired += shape.repaired() ? 1 : 0;
    }

    return new Result(Map.copyOf(shapes), Map.copyOf(displayPoints),
        Map.copyOf(borderLengths), ringsTraced, repaired);
  }

  private static List<Ring> traceRings(LongList edgeList, int vertexWidth) {
    long[] edges = edgeList.toArray();
    if (edges.length == 0) {
      return List.of();
    }
    Map<Integer, IntList> outgoing = new HashMap<>();
    for (int index = 0; index < edges.length; index++) {
      int start = edgeStart(edges[index]);
      outgoing.computeIfAbsent(start, ignored -> new IntList(4)).add(index);
    }

    boolean[] used = new boolean[edges.length];
    List<Ring> rings = new ArrayList<>();
    for (int seed = 0; seed < edges.length; seed++) {
      if (used[seed]) {
        continue;
      }
      int startVertex = edgeStart(edges[seed]);
      IntList path = new IntList(64);
      path.add(startVertex);
      int currentEdge = seed;
      boolean closed = false;
      int guard = 0;
      while (true) {
        if (used[currentEdge]) {
          break;
        }
        used[currentEdge] = true;
        int end = edgeEnd(edges[currentEdge]);
        if (end == startVertex) {
          closed = true;
          break;
        }
        path.add(end);
        int next = chooseNext(currentEdge, end, edges, outgoing, used, vertexWidth);
        if (next < 0) {
          break;
        }
        currentEdge = next;
        if (++guard > edges.length + 1) {
          throw new Hoi4ImportException("HOI4_POLYGON_TRACE_FAILED", "boundary trace 超过边数上限");
        }
      }
      if (!closed) {
        continue;
      }
      IntList simplified = removeCollinear(path, vertexWidth);
      if (simplified.size() < 3 || Math.abs(ringArea(simplified, vertexWidth)) <= AREA_EPSILON) {
        continue;
      }
      List<Vec2> points = new ArrayList<>(simplified.size());
      for (int i = 0; i < simplified.size(); i++) {
        int vertex = simplified.get(i);
        points.add(new Vec2(vertex % vertexWidth, vertex / vertexWidth));
      }
      rings.add(new Ring(points));
    }
    return rings;
  }

  private static int chooseNext(int currentEdge, int endVertex, long[] edges,
      Map<Integer, IntList> outgoing, boolean[] used, int vertexWidth) {
    IntList candidates = outgoing.get(endVertex);
    if (candidates == null) {
      return -1;
    }
    int only = -1;
    int candidateCount = 0;
    for (int i = 0; i < candidates.size(); i++) {
      int candidate = candidates.get(i);
      if (!used[candidate]) {
        only = candidate;
        candidateCount++;
      }
    }
    if (candidateCount <= 1) {
      return only;
    }

    int startX = edgeStart(edges[currentEdge]) % vertexWidth;
    int startY = edgeStart(edges[currentEdge]) / vertexWidth;
    int endX = endVertex % vertexWidth;
    int endY = endVertex / vertexWidth;
    int inX = endX - startX;
    int inY = endY - startY;

    int best = -1;
    double bestAngle = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < candidates.size(); i++) {
      int candidate = candidates.get(i);
      if (used[candidate]) {
        continue;
      }
      int nextX = edgeEnd(edges[candidate]) % vertexWidth;
      int nextY = edgeEnd(edges[candidate]) / vertexWidth;
      int outX = nextX - endX;
      int outY = nextY - endY;
      double cross = (double) inX * outY - (double) inY * outX;
      double dot = (double) inX * outX + (double) inY * outY;
      double angle = Math.atan2(cross, dot);
      if (Math.abs(Math.abs(angle) - Math.PI) < 1e-9 && candidateCount > 1) {
        continue;
      }
      if (angle > bestAngle) {
        bestAngle = angle;
        best = candidate;
      }
    }
    return best >= 0 ? best : only;
  }

  private static IntList removeCollinear(IntList path, int vertexWidth) {
    IntList current = path;
    boolean changed = true;
    while (changed && current.size() > 3) {
      changed = false;
      IntList next = new IntList(current.size());
      int size = current.size();
      for (int i = 0; i < size; i++) {
        int prev = current.get((i + size - 1) % size);
        int value = current.get(i);
        int after = current.get((i + 1) % size);
        int px = prev % vertexWidth;
        int py = prev / vertexWidth;
        int cx = value % vertexWidth;
        int cy = value / vertexWidth;
        int ax = after % vertexWidth;
        int ay = after / vertexWidth;
        long cross = (long) (cx - px) * (ay - cy) - (long) (cy - py) * (ax - cx);
        long dot = (long) (cx - px) * (ax - cx) + (long) (cy - py) * (ay - cy);
        if (cross == 0 && dot >= 0) {
          changed = true;
        } else {
          next.add(value);
        }
      }
      current = next;
      if (current.size() < 3) {
        return current;
      }
    }
    return current;
  }

  private static ShapeResult ringsToShape(int provinceId, List<Ring> rings) {
    List<RingInfo> infos = new ArrayList<>();
    for (Ring ring : rings) {
      LinearRing linearRing = toLinearRing(ring);
      Polygon polygon;
      try {
        polygon = FACTORY.createPolygon(linearRing);
      } catch (RuntimeException e) {
        throw new Hoi4ImportException("HOI4_POLYGON_INVALID",
            "省 " + provinceId + " 的 ring 无法构造 polygon", e);
      }
      double area = polygon.getArea();
      if (area <= AREA_EPSILON) {
        continue;
      }
      infos.add(new RingInfo(ring, polygon, area, -1, -1));
    }
    if (infos.isEmpty()) {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID", "省 " + provinceId + " 没有有效 ring");
    }
    infos.sort(Comparator.comparingDouble(RingInfo::area).reversed());

    for (int i = 0; i < infos.size(); i++) {
      RingInfo info = infos.get(i);
      Coordinate coordinate = info.polygon().getCoordinate();
      if (coordinate == null) {
        continue;
      }
      Point point = FACTORY.createPoint(coordinate);
      int parent = -1;
      double parentArea = Double.POSITIVE_INFINITY;
      for (int j = 0; j < i; j++) {
        RingInfo candidate = infos.get(j);
        if (candidate.polygon().contains(point) && candidate.area() < parentArea) {
          parent = j;
          parentArea = candidate.area();
        }
      }
      infos.set(i, new RingInfo(info.ring(), info.polygon(), info.area(),
          parent < 0 ? 0 : infos.get(parent).depth() + 1, parent));
    }

    List<Polygon> polygons = new ArrayList<>();
    for (int i = 0; i < infos.size(); i++) {
      RingInfo info = infos.get(i);
      if ((info.depth() & 1) != 0) {
        continue;
      }
      List<LinearRing> holes = new ArrayList<>();
      for (int j = 0; j < infos.size(); j++) {
        RingInfo child = infos.get(j);
        if (child.parent() == i && (child.depth() & 1) != 0) {
          holes.add(toLinearRing(child.ring()));
        }
      }
      try {
        polygons.add(FACTORY.createPolygon(toLinearRing(info.ring()), holes.toArray(LinearRing[]::new)));
      } catch (RuntimeException e) {
        throw new Hoi4ImportException("HOI4_POLYGON_INVALID",
            "省 " + provinceId + " 的 shell/hole 无法构造 polygon", e);
      }
    }
    if (polygons.isEmpty()) {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID", "省 " + provinceId + " 没有 shell polygon");
    }

    Geometry geometry = polygons.size() == 1
        ? polygons.get(0)
        : FACTORY.createMultiPolygon(polygons.toArray(Polygon[]::new));
    boolean repaired = false;
    if (!geometry.isValid()) {
      geometry = GeometryFixer.fix(geometry);
      repaired = true;
    }
    if (geometry.isEmpty() || !geometry.isValid()) {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID",
          "省 " + provinceId + " 的 polygon 无法修复");
    }
    ProvinceShape shape = toProvinceShape(provinceId, geometry);
    Coordinate interior = geometry.getInteriorPoint().getCoordinate();
    if (interior == null || !Double.isFinite(interior.x) || !Double.isFinite(interior.y)) {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID", "省 " + provinceId + " 无法求显示点");
    }
    return new ShapeResult(shape, new Vec2(interior.x, interior.y), repaired);
  }

  private static ProvinceShape toProvinceShape(int provinceId, Geometry geometry) {
    List<PolygonPart> parts = new ArrayList<>();
    if (geometry instanceof Polygon polygon) {
      parts.add(toPart(provinceId, polygon));
    } else if (geometry instanceof MultiPolygon multiPolygon) {
      for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
        parts.add(toPart(provinceId, (Polygon) multiPolygon.getGeometryN(i)));
      }
    } else if (geometry instanceof GeometryCollection collection) {
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        parts.addAll(toProvinceShape(provinceId, collection.getGeometryN(i)).parts());
      }
    } else {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID",
          "省 " + provinceId + " 修复后不是 polygon: " + geometry.getGeometryType());
    }
    return new ProvinceShape(parts);
  }

  private static PolygonPart toPart(int provinceId, Polygon polygon) {
    Ring outer = toRing(polygon.getExteriorRing());
    List<Ring> holes = new ArrayList<>();
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
      holes.add(toRing(polygon.getInteriorRingN(i)));
    }
    if (outer.points().size() < 3) {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID", "省 " + provinceId + " 的外环退化");
    }
    return new PolygonPart(outer, holes);
  }

  private static Ring toRing(LineString line) {
    List<Vec2> points = new ArrayList<>(Math.max(0, line.getNumPoints() - 1));
    for (int i = 0; i < line.getNumPoints() - 1; i++) {
      Coordinate coordinate = line.getCoordinateN(i);
      Vec2 point = new Vec2(coordinate.x, coordinate.y);
      if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
        points.add(point);
      }
    }
    if (points.size() > 1 && points.get(0).equals(points.get(points.size() - 1))) {
      points.remove(points.size() - 1);
    }
    if (points.size() < 3) {
      throw new Hoi4ImportException("HOI4_POLYGON_INVALID", "ring 少于 3 个不同点");
    }
    return new Ring(points);
  }

  private static LinearRing toLinearRing(Ring ring) {
    List<Vec2> closed = ring.closedPoints();
    Coordinate[] coordinates = new Coordinate[closed.size()];
    for (int i = 0; i < closed.size(); i++) {
      Vec2 point = closed.get(i);
      coordinates[i] = new Coordinate(point.x(), point.y());
    }
    return FACTORY.createLinearRing(coordinates);
  }

  private static double ringArea(IntList ring, int vertexWidth) {
    double twice = 0;
    for (int i = 0; i < ring.size(); i++) {
      int a = ring.get(i);
      int b = ring.get((i + 1) % ring.size());
      double ax = a % vertexWidth;
      double ay = a / vertexWidth;
      double bx = b % vertexWidth;
      double by = b / vertexWidth;
      twice += ax * by - bx * ay;
    }
    return Math.abs(twice) / 2.0;
  }

  private static void addBorder(Map<Long, Integer> borderLengths, int a, int b) {
    int min = Math.min(a, b);
    int max = Math.max(a, b);
    long key = ((long) min << 32) | (max & 0xffffffffL);
    borderLengths.merge(key, 1, Integer::sum);
  }

  static int borderA(long key) {
    return (int) (key >>> 32);
  }

  static int borderB(long key) {
    return (int) key;
  }

  private static long packEdge(int start, int end) {
    return ((long) start << 32) | (end & 0xffffffffL);
  }

  private static int edgeStart(long edge) {
    return (int) (edge >>> 32);
  }

  private static int edgeEnd(long edge) {
    return (int) edge;
  }

  record Result(
      Map<Integer, ProvinceShape> shapes,
      Map<Integer, Vec2> displayPoints,
      Map<Long, Integer> borderLengths,
      int ringsTraced,
      int repaired) {}

  private record ShapeResult(ProvinceShape shape, Vec2 displayPoint, boolean repaired) {}

  private record RingInfo(Ring ring, Polygon polygon, double area, int depth, int parent) {}

  private static final class LongList {
    private long[] values;
    private int size;

    LongList(int initialCapacity) {
      values = new long[Math.max(1, initialCapacity)];
    }

    void add(long value) {
      if (size == values.length) {
        long[] next = new long[values.length * 2];
        System.arraycopy(values, 0, next, 0, size);
        values = next;
      }
      values[size++] = value;
    }

    int size() {
      return size;
    }

    long[] toArray() {
      long[] result = new long[size];
      System.arraycopy(values, 0, result, 0, size);
      return result;
    }
  }

  private static final class IntList {
    private int[] values;
    private int size;

    IntList(int initialCapacity) {
      values = new int[Math.max(1, initialCapacity)];
    }

    void add(int value) {
      if (size == values.length) {
        int[] next = new int[values.length * 2];
        System.arraycopy(values, 0, next, 0, size);
        values = next;
      }
      values[size++] = value;
    }

    int get(int index) {
      Objects.checkIndex(index, size);
      return values[index];
    }

    int size() {
      return size;
    }
  }
}
