package io.mosire.worldsim.app;

import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.JtsGeometry;
import io.mosire.worldsim.map.PolygonPart;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Ring;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.map.WorldMap;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A read-only calibration and gazetteer for one map sheet. */
final class WorldGeography {
  record Place(String provinceId, List<String> names, double longitude, double latitude, String source) {
    Place {
      requireText(provinceId);
      requireText(source);
      names = List.copyOf(names);
      if (names.isEmpty()) throw new IllegalArgumentException("地名至少需要一个名称");
      names.forEach(WorldGeography::requireText);
      requireCoordinates(longitude, latitude);
    }
  }

  record Border(String name, String date, String source, List<List<Vec2>> paths) {
    Border {
      requireText(name);
      requireText(source);
      java.time.LocalDate.parse(date);
      paths = paths.stream().map(List::copyOf).toList();
      if (paths.stream().anyMatch(line -> line.size() < 2)) throw new IllegalArgumentException("边界线至少需要两个点");
    }
  }

  record Check(int controls, int tested, int matched, double maxError) {
    Check {
      if (controls < 3 || tested < 1 || matched < 0 || matched > tested || !Double.isFinite(maxError) || maxError < 0) {
        throw new IllegalArgumentException("配准验收记录无效");
      }
    }
  }

  record TerritoryReference(String date, String source, Map<String, String> owners) {
    TerritoryReference {
      java.time.LocalDate.parse(date);
      requireText(source);
      owners = Map.copyOf(owners);
      owners.forEach((province, country) -> {
        requireText(province);
        requireText(country);
      });
    }
  }

  record DistanceReference(String method, String source, String referenceHash, double mapErrorRadius) {}

  record Reference(String format, String sheet, String base, String baseHash, double width, double height,
                   String source, List<Vec2> coverage, List<Double> affine, double errorRadius,
                   Check check, List<Place> places, List<Border> borders, TerritoryReference territory,
                   RiverReference rivers) {
    Reference {
      if (!"worldsim-geo-v1".equals(format)) throw new IllegalArgumentException("未知地理参考格式");
      requireText(sheet);
      requireText(base);
      if (baseHash == null || !baseHash.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("缺少有效的基准 hash");
      }
      if (!positive(width) || !positive(height) || !positive(errorRadius)) {
        throw new IllegalArgumentException("地图尺寸和误差半径必须为正");
      }
      requireText(source);
      coverage = List.copyOf(coverage);
      coverage.forEach(point -> requireCoordinates(point.x(), point.y()));
      affine = List.copyOf(affine);
      if (affine.size() != 6 || affine.stream().anyMatch(value -> !Double.isFinite(value))) {
        throw new IllegalArgumentException("仿射变换需要六个有限系数");
      }
      double determinant = affine.get(1) * affine.get(5) - affine.get(2) * affine.get(4);
      if (!Double.isFinite(determinant) || determinant == 0) throw new IllegalArgumentException("仿射变换不可逆");
      Objects.requireNonNull(check, "配准验收记录");
      if (errorRadius < check.maxError()) throw new IllegalArgumentException("误差半径小于验收最大误差");
      places = List.copyOf(places);
      borders = List.copyOf(borders);
      if (rivers != null && rivers.lines().stream().flatMap(line -> line.points().stream())
          .anyMatch(point -> point.x() < 0 || point.x() > width || point.y() < 0 || point.y() > height)) {
        throw new IllegalArgumentException("河流参考超出地图范围");
      }
    }
  }

  private record Candidate(String provinceId, String name, String address, String stableId, String stateId,
                           String kind, Boolean contains, Double distance, Place place) {}

  private final Reference reference;
  private final String version;
  private final Polygon coverage;
  private final Map<String, List<Place>> names = new LinkedHashMap<>();
  private WorldMap indexedMap;
  private STRtree index;

  WorldGeography(String json) {
    reference = JsonCodec.fromJson(json, Reference.class);
    version = JsonCodec.sha256(json);
    coverage = JtsGeometry.toPolygon(new PolygonPart(new Ring(reference.coverage()), List.of()));
    if (!coverage.isValid() || coverage.getArea() <= 0) throw new IllegalArgumentException("配准覆盖范围无效");
    for (Place place : reference.places()) {
      for (String name : place.names().stream().map(WorldGeography::normalize).distinct().toList()) {
        names.computeIfAbsent(name, ignored -> new ArrayList<>()).add(place);
      }
    }
  }

  Reference reference() { return reference; }

  Map<String, Object> lookup(WorldApplicationService.SavedMap saved, String name, Double longitude, Double latitude) {
    var result = context(saved);
    if (name != null) {
      var matches = new LinkedHashMap<String, Candidate>();
      for (Place place : names.getOrDefault(normalize(name), List.of())) {
        Province province = saved.map().provinces().get(place.provinceId());
        if (province != null) matches.putIfAbsent(province.id(), candidate(saved, province, null, null, place));
      }
      return finish(result, matches.isEmpty() ? "not-found" : matches.size() == 1 ? "resolved" : "ambiguous",
          List.copyOf(matches.values()));
    }

    Vec2 wgs84 = new Vec2(longitude, latitude);
    if (!coverage.covers(JtsGeometry.point(wgs84))) return finish(result, "outside-coverage", List.of());
    Vec2 projected = project(wgs84);
    if (projected.x() < 0 || projected.x() > saved.map().width()
        || projected.y() < 0 || projected.y() > saved.map().height()) {
      return finish(result, "outside-coverage", List.of());
    }
    result.put("point", projected);
    var position = JtsGeometry.point(projected);
    var area = new Envelope(position.getEnvelopeInternal());
    double radius = reference.errorRadius();
    area.expandBy(radius);
    var matches = new ArrayList<Candidate>();
    boolean inside = false;
    for (Object row : index(saved.map()).query(area)) {
      Province province = (Province) row;
      var shape = JtsGeometry.toGeometry(province.shape());
      double distance = shape.distance(position);
      if (distance > radius) continue;
      boolean contains = shape.covers(position);
      if (contains && shape.getBoundary().distance(position) > radius) inside = true;
      matches.add(candidate(saved, province, contains, distance, null));
    }
    matches.sort(Comparator.comparing(Candidate::contains).reversed()
        .thenComparing(Candidate::distance).thenComparing(Candidate::provinceId));
    return finish(result, matches.isEmpty() ? "not-found" : inside && matches.size() == 1 ? "resolved" : "ambiguous", matches);
  }

  Map<String, Object> overlay(WorldApplicationService.SavedMap saved) {
    var result = context(saved);
    result.put("borders", reference.borders());
    return result;
  }

  Map<String, Object> locate(WorldApplicationService.SavedMap saved, Province province) {
    var result = context(saved);
    result.put("address", WorldQueries.address(saved.node(), "map", "province", province.id()));
    result.put("stableId", "province:" + province.id());
    result.put("places", reference.places().stream().filter(place -> place.provinceId().equals(province.id())).toList());
    var position = unproject(province.displayPoint());
    result.put("status", position == null ? "outside-coverage" : "estimated");
    if (position != null) result.put("position", Map.of("longitude", position.x(), "latitude", position.y()));
    return result;
  }

  DistanceReference distanceReference() {
    return new DistanceReference("wgs84-great-circle", reference.source(), version, reference.errorRadius());
  }

  Double distanceKm(Vec2 from, Vec2 to) {
    Vec2 a = unproject(from);
    Vec2 b = unproject(to);
    if (a == null || b == null) return null;
    double halfLatitude = Math.toRadians(b.y() - a.y()) / 2;
    double halfLongitude = Math.toRadians(b.x() - a.x()) / 2;
    double haversine = Math.pow(Math.sin(halfLatitude), 2) + Math.pow(Math.sin(halfLongitude), 2)
        * Math.cos(Math.toRadians(a.y())) * Math.cos(Math.toRadians(b.y()));
    double meanEarthRadiusKm = 6371.0088;
    return 2 * meanEarthRadiusKm * Math.asin(Math.sqrt(Math.min(1, haversine)));
  }

  private LinkedHashMap<String, Object> context(WorldApplicationService.SavedMap saved) {
    return new LinkedHashMap<>(Map.of("node", saved.node().label(), "stateHash", saved.stateHash(),
        "sheet", reference.sheet(), "referenceHash", version, "source", reference.source(),
        "errorRadius", reference.errorRadius(), "check", reference.check()));
  }

  private static Map<String, Object> finish(Map<String, Object> result, String status, List<Candidate> candidates) {
    result.put("status", status);
    result.put("candidates", candidates);
    return result;
  }

  private static Candidate candidate(WorldApplicationService.SavedMap saved, Province province,
                                     Boolean contains, Double distance, Place place) {
    return new Candidate(province.id(), province.name(), WorldQueries.address(saved.node(), "map", "province", province.id()),
        "province:" + province.id(), province.stateId(), province.kind(), contains, distance, place);
  }

  private Vec2 project(Vec2 wgs84) {
    double x = Math.toRadians(wgs84.x());
    double y = 1.25 * Math.log(Math.tan(Math.PI / 4 + .4 * Math.toRadians(wgs84.y())));
    var a = reference.affine();
    return new Vec2(a.get(0) + a.get(1) * x + a.get(2) * y, a.get(3) + a.get(4) * x + a.get(5) * y);
  }

  private Vec2 unproject(Vec2 point) {
    var a = reference.affine();
    double x = point.x() - a.get(0);
    double y = point.y() - a.get(3);
    double determinant = a.get(1) * a.get(5) - a.get(2) * a.get(4);
    double longitude = Math.toDegrees((x * a.get(5) - y * a.get(2)) / determinant);
    double millerY = (a.get(1) * y - a.get(4) * x) / determinant;
    double latitude = Math.toDegrees((Math.atan(Math.exp(.8 * millerY)) - Math.PI / 4) / .4);
    if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
        || !coverage.covers(JtsGeometry.point(new Vec2(longitude, latitude)))) return null;
    return new Vec2(longitude, latitude);
  }

  private STRtree index(WorldMap map) {
    if (indexedMap != map) {
      var next = new STRtree();
      for (Province province : map.provinces().values()) {
        var bounds = new Envelope();
        for (var part : province.shape().parts()) {
          for (Vec2 vertex : part.outer().points()) bounds.expandToInclude(vertex.x(), vertex.y());
        }
        next.insert(bounds, province);
      }
      next.build();
      indexedMap = map;
      index = next;
    }
    return index;
  }

  static void requireCoordinates(double longitude, double latitude) {
    if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
        || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
      throw new IllegalArgumentException("经纬度超出 WGS84 范围");
    }
  }

  private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }

  private static void requireText(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("地理参考缺少名称或来源");
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
        .replaceAll("\\p{M}", "");
  }
}
