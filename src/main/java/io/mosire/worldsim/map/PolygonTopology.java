package io.mosire.worldsim.map;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashMap;

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

  /** Stable issue keys permit legacy source discrepancies to be reported without introducing new ones. */
  public static Set<String> consistencyIssues(WorldMap map) {
    Map<String, Adjacency> derived = new HashMap<>();
    for (Adjacency adjacency : deriveAdjacencies(map)) derived.put(pair(adjacency.a(), adjacency.b()), adjacency);
    Set<String> ordinary = new TreeSet<>();
    Set<String> special = new TreeSet<>();
    Set<String> issues = new TreeSet<>();
    for (Adjacency adjacency : map.adjacencies()) {
      if (adjacency.kind().startsWith("special")) special.add(pair(adjacency.a(), adjacency.b()));
      else {
        String edge = pair(adjacency.a(), adjacency.b());
        if (!ordinary.add(edge)) issues.add("DUPLICATE:" + edge);
        Adjacency expected = derived.get(edge);
        if (expected == null) issues.add("STALE:" + edge);
        else {
          if (!adjacency.kind().equals(expected.kind())) issues.add("KIND:" + edge + ":" + adjacency.kind() + ":" + expected.kind());
          if (Math.abs(adjacency.borderLength() - expected.borderLength()) > LENGTH_EPSILON) {
            issues.add("BORDER:" + edge + ":" + adjacency.borderLength() + ":" + expected.borderLength());
          }
        }
      }
    }
    for (String pair : derived.keySet()) if (!ordinary.contains(pair)) issues.add("MISSING:" + pair);
    for (Railway railway : map.railways().values()) {
      for (int i = 1; i < railway.provincePath().size(); i++) {
        String edge = pair(railway.provincePath().get(i - 1), railway.provincePath().get(i));
        if (!derived.containsKey(edge) && !special.contains(edge)) issues.add("RAIL:" + railway.id() + ":" + edge);
      }
    }
    return Set.copyOf(issues);
  }

  private static String pair(String a, String b) {
    return io.mosire.worldsim.json.JsonCodec.canonicalJson(
        a.compareTo(b) < 0 ? List.of(a, b) : List.of(b, a));
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
