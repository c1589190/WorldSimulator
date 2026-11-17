package io.mosire.worldsim.map;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 从共享多边形边界推导省邻接。栅格/hex 邻接不再是权威。 */
public final class PolygonTopology {
  private static final double LENGTH_EPSILON = 1e-6;

  private PolygonTopology() {}

  public static List<Adjacency> deriveAdjacencies(WorldMap map) {
    STRtree index = new STRtree();
    for (Map.Entry<String, Province> entry : map.provinces().entrySet()) {
      Geometry geometry = JtsGeometry.toGeometry(entry.getValue().shape());
      index.insert(geometry.getEnvelopeInternal(), new Item(entry.getKey(), geometry));
    }
    index.build();
    List<Adjacency> out = new ArrayList<>();
    for (Map.Entry<String, Province> entry : map.provinces().entrySet()) {
      Geometry geometry = JtsGeometry.toGeometry(entry.getValue().shape());
      @SuppressWarnings("unchecked")
      List<Item> hits = (List<Item>) index.query(geometry.getEnvelopeInternal());
      for (Item other : hits) {
        if (entry.getKey().compareTo(other.id()) >= 0) {
          continue;
        }
        if (!geometry.touches(other.geometry())) {
          continue;
        }
        double shared = geometry.getBoundary().intersection(other.geometry().getBoundary()).getLength();
        if (shared <= LENGTH_EPSILON) {
          continue;
        }
        Province left = map.provinces().get(entry.getKey());
        Province right = map.provinces().get(other.id());
        out.add(new Adjacency(left.id(), right.id(), kind(left, right), shared));
      }
    }
    out.sort(Comparator.comparing(Adjacency::a).thenComparing(Adjacency::b));
    return List.copyOf(out);
  }

  private static String kind(Province a, Province b) {
    if (a.kind().equals("sea") || b.kind().equals("sea")) {
      return "sea";
    }
    if (a.kind().equals("lake") || b.kind().equals("lake")) {
      return "lake";
    }
    return "land";
  }

  private record Item(String id, Geometry geometry) {}
}
