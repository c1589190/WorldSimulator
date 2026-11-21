package io.mosire.worldsim.app;

import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.JtsGeometry;
import io.mosire.worldsim.map.LandEdgeConditions;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.WorldMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Map facts that can be reused across movement estimates and engagement previews. */
final class LandLinks {
  private static final double RIVER_REFERENCE_TOLERANCE = 1.0;
  private static final LandEdgeConditions.River UNKNOWN = new LandEdgeConditions.River("unknown", null, null, null, null, null);
  private record Pair(String a, String b) {
    static Pair of(String a, String b) { return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a); }
  }
  private record RiverLine(LineString geometry, int width) {}

  private final Map<Pair, Adjacency> edges = new HashMap<>();
  private final Map<String, java.util.SortedSet<String>> neighbors = new HashMap<>();
  private final Map<Pair, List<LandEdgeConditions.Track>> tracks = new HashMap<>();
  private final Map<Pair, LandEdgeConditions.River> riverByEdge = new HashMap<>();
  private final RiverReference reference;
  private final STRtree rivers = new STRtree();

  LandLinks(WorldMap map, RiverReference reference) {
    this.reference = reference;
    for (var edge : map.adjacencies()) {
      if (edge.permitsLandMarch()) {
        edges.put(new Pair(edge.a(), edge.b()), edge);
        neighbors.computeIfAbsent(edge.a(), ignored -> new java.util.TreeSet<>()).add(edge.b());
        neighbors.computeIfAbsent(edge.b(), ignored -> new java.util.TreeSet<>()).add(edge.a());
      }
    }
    map.railways().values().stream().sorted(Comparator.comparing(railway -> railway.id())).forEach(railway -> {
      var operation = railway.operation();
      var track = new LandEdgeConditions.Track(railway.id(), railway.level(), operation == null ? "unknown" : operation.status(),
          operation == null ? null : operation.tonnesPerDay(), operation == null ? null : operation.evidence());
      var path = railway.provincePath();
      for (int index = 1; index < path.size(); index++) {
        var pair = Pair.of(path.get(index - 1), path.get(index));
        if (edges.containsKey(pair)) {
          var lines = tracks.computeIfAbsent(pair, ignored -> new ArrayList<>());
          if (lines.isEmpty() || !lines.getLast().equals(track)) lines.add(track);
        }
      }
    });
    if (reference != null) {
      var factory = new GeometryFactory();
      for (var line : reference.lines()) {
        var points = line.points().stream().map(point -> new Coordinate(point.x(), point.y())).toArray(Coordinate[]::new);
        var geometry = factory.createLineString(points);
        rivers.insert(geometry.getEnvelopeInternal(), new RiverLine(geometry, line.width()));
      }
    }
    rivers.build();
  }

  LandEdgeConditions edge(Province from, Province to) {
    var pair = Pair.of(from.id(), to.id());
    var edge = edges.get(pair);
    if (edge == null) return null;
    var river = riverByEdge.computeIfAbsent(pair, ignored -> river(edge, from, to));
    return new LandEdgeConditions(from.terrain(), to.terrain(), river, tracks.getOrDefault(pair, List.of()));
  }

  List<String> neighbors(String province) {
    var ids = neighbors.get(province);
    return ids == null ? List.of() : List.copyOf(ids);
  }

  List<LandEdgeConditions.Track> railTracks(String from, String to) {
    return tracks.getOrDefault(Pair.of(from, to), List.of());
  }

  static String shapeHash(Province from, Province to) {
    var shapes = from.id().compareTo(to.id()) < 0 ? List.of(from.shape(), to.shape()) : List.of(to.shape(), from.shape());
    return JsonCodec.sha256(JsonCodec.canonicalJson(shapes));
  }

  private LandEdgeConditions.River river(Adjacency edge, Province from, Province to) {
    var recorded = edge.riverCrossing();
    if (recorded != null && recorded.shapeHash().equals(shapeHash(from, to))) {
      return new LandEdgeConditions.River(recorded.crosses() ? "confirmed-crossing" : "confirmed-clear",
          null, null, recorded.evidence().source(), null, recorded.evidence());
    }
    if (reference == null) return UNKNOWN;
    var boundary = JtsGeometry.toGeometry(from.shape()).getBoundary()
        .intersection(JtsGeometry.toGeometry(to.shape()).getBoundary());
    if (boundary.getLength() <= 0) return UNKNOWN;
    var near = new Envelope(boundary.getEnvelopeInternal());
    near.expandBy(RIVER_REFERENCE_TOLERANCE);
    int rank = 0;
    int width = 0;
    for (Object hit : rivers.query(near)) {
      var line = (RiverLine) hit;
      var intersection = boundary.intersection(line.geometry());
      int candidateRank = intersection.getLength() > 0 ? 3 : !intersection.isEmpty() ? 2
          : boundary.isWithinDistance(line.geometry(), RIVER_REFERENCE_TOLERANCE) ? 1 : 0;
      if (candidateRank > rank) {
        rank = candidateRank;
        width = line.width();
      } else if (rank != 0 && candidateRank == rank) {
        width = Math.max(width, line.width());
      }
    }
    String relation = switch (rank) {
      case 3 -> "along";
      case 2 -> "intersects";
      case 1 -> "near";
      default -> null;
    };
    return new LandEdgeConditions.River(rank == 0 ? "none-in-reference" : "candidate", relation,
        rank == 0 ? null : width, reference.source(), reference.sourceHash(), null);
  }
}
