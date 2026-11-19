package io.mosire.worldsim.app.viewer;

import io.mosire.worldsim.app.RiverReference;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.JtsGeometry;
import io.mosire.worldsim.map.PolygonPart;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Ring;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.map.WorldMap;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derived render and picking data for a committed map. */
final class ViewerMap {
  static final int TILE_SIZE = 512;
  static final int MAX_LEVEL = 5;
  private static final int OVERVIEW_SIZE = 2048;
  private static final long CACHE_BYTES = 24L * 1024 * 1024;
  private static final Color BACKGROUND = new Color(0x1a303d);
  private static final Color SEA = new Color(0x223f51);
  private static final Color LAKE = new Color(0x5c879b);
  private static final Color LAND = new Color(0xc5c3b2);
  private static final Color MISSING_OWNER = new Color(0xff66ba);
  private static final Color LAND_BORDER = new Color(43, 52, 53, 95);
  private static final Color WATER_BORDER = new Color(128, 161, 177, 63);
  private static final Color RIVER = new Color(0x3376ab);
  private static final List<Color> AREA_COLORS = List.of(
      new Color(0x8da993), new Color(0xd7bc8d), new Color(0xaaa2c7), new Color(0x83a6be),
      new Color(0xd59684), new Color(0xaab371), new Color(0xc98eaa), new Color(0x8cbab3),
      new Color(0xb7a089), new Color(0x9295b6), new Color(0xcfa46a), new Color(0x74918b));
  private static final Map<String, Color> TERRAIN = Map.of(
      "plains", new Color(0xd4d5ad), "forest", new Color(0xaeba91),
      "hills", new Color(0xc4b995), "mountain", new Color(0xb6a491),
      "desert", new Color(0xe7c99d), "urban", new Color(0xddab8e),
      "marsh", new Color(0xadc1a5), "jungle", new Color(0x90af85));

  enum Layer {
    TERRAIN, STATE, REGION, OWNER, CONTROLLER, OWNER_GAPS;

    static Layer parse(String text) {
      return switch (text) {
        case "terrain" -> TERRAIN;
        case "state" -> STATE;
        case "region" -> REGION;
        case "owner" -> OWNER;
        case "controller" -> CONTROLLER;
        case "owner-gaps" -> OWNER_GAPS;
        default -> throw new IllegalArgumentException("未知地图图层: " + text);
      };
    }

    boolean grouped() { return this == STATE || this == REGION; }

    String areaId(Province province) { return this == STATE ? province.stateId() : province.regionId(); }
  }

  record Area(ProvinceShape shape, Vec2 point) {}

  private record Drawing(Province province, Path2D path) {}
  private record AreaDrawing(String id, Path2D path, Color color) {}
  private record RiverDrawing(int width, Path2D path) {}
  private record ImageKey(Layer layer, int level, int x, int y, CountryState politics, Set<String> gaps) {}

  private final WorldMap map;
  private final STRtree index = new STRtree();
  private final STRtree rivers = new STRtree();
  private final Map<Layer, Map<String, Area>> areas = new EnumMap<>(Layer.class);
  private final Map<Layer, STRtree> areaLayers = new EnumMap<>(Layer.class);
  private final LinkedHashMap<ImageKey, byte[]> images = new LinkedHashMap<>(16, .75f, true);
  private long imageBytes;

  ViewerMap(WorldMap map, RiverReference reference) {
    this.map = map;
    for (Province province : map.provinces().values()) {
      Path2D path = path(province.shape());
      index.insert(envelope(path), new Drawing(province, path));
    }
    index.build();
    if (reference != null) {
      for (var line : reference.lines()) {
        Path2D path = new Path2D.Double();
        append(path, line.points());
        rivers.insert(envelope(path), new RiverDrawing(line.width(), path));
      }
    }
    rivers.build();
  }

  static double overviewScale(WorldMap map) {
    return Math.min(64, OVERVIEW_SIZE / Math.max(map.width(), map.height()));
  }

  static boolean validTile(WorldMap map, int level, int x, int y) {
    if (level < 1 || level > MAX_LEVEL || x < 0 || y < 0) return false;
    double scale = Math.scalb(overviewScale(map), level);
    return (double) x * TILE_SIZE < map.width() * scale && (double) y * TILE_SIZE < map.height() * scale;
  }

  Province pick(Vec2 point) {
    if (point.x() < 0 || point.x() > map.width() || point.y() < 0 || point.y() > map.height()) return null;
    var location = JtsGeometry.point(point);
    for (Drawing drawing : drawings(location.getEnvelopeInternal())) {
      if (JtsGeometry.toGeometry(drawing.province().shape()).covers(location)) return drawing.province();
    }
    return null;
  }

  synchronized Area area(Layer layer, String id) {
    var byId = areas.computeIfAbsent(layer, ignored -> new HashMap<>());
    return byId.computeIfAbsent(id, key -> mergeArea(layer == Layer.STATE
        ? map.states().get(key).provinceIds()
        : map.strategicRegions().get(key).provinceIds()));
  }

  private Area mergeArea(List<String> ids) {
    if (ids.isEmpty()) return null;
    if (ids.size() == 1) {
      var province = map.provinces().get(ids.getFirst());
      var point = JtsGeometry.toGeometry(province.shape()).getInteriorPoint().getCoordinate();
      return new Area(province.shape(), new Vec2(point.x, point.y));
    }
    var shapes = ids.stream().map(id -> JtsGeometry.toGeometry(map.provinces().get(id).shape())).toList();
    Geometry merged = OverlayNGRobust.union(shapes);
    var point = merged.getInteriorPoint().getCoordinate();
    return new Area(shape(merged), new Vec2(point.x, point.y));
  }

  synchronized byte[] image(Layer layer, int level, int x, int y, CountryState politics, Set<String> gaps) throws IOException {
    var coloring = switch (layer) {
      case OWNER, CONTROLLER, OWNER_GAPS -> politics;
      default -> null;
    };
    var key = new ImageKey(layer, level, x, y, coloring, gaps);
    byte[] cached = images.get(key);
    if (cached != null) return cached;
    byte[] png = render(key);
    while (!images.isEmpty() && imageBytes + png.length > CACHE_BYTES) {
      imageBytes -= images.pollFirstEntry().getValue().length;
    }
    if (png.length <= CACHE_BYTES) {
      images.put(key, png);
      imageBytes += png.length;
    }
    return png;
  }

  private byte[] render(ImageKey key) throws IOException {
    double scale = Math.scalb(overviewScale(map), key.level());
    boolean overview = key.level() == 0;
    int width = overview ? pixels(map.width(), scale) : TILE_SIZE;
    int height = overview ? pixels(map.height(), scale) : TILE_SIZE;
    int left = key.x() * TILE_SIZE;
    int top = key.y() * TILE_SIZE;
    Envelope viewport = new Envelope(left / scale, (left + width) / scale, top / scale, (top + height) / scale);
    viewport.expandBy(2 / scale);

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(BACKGROUND);
      graphics.fillRect(0, 0, width, height);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.translate(-left, -top);
      graphics.scale(scale, scale);
      graphics.clip(new Rectangle2D.Double(0, 0, map.width(), map.height()));
      graphics.setStroke(new BasicStroke((float) (.62 / scale)));
      for (Drawing drawing : drawings(viewport)) {
        Province province = drawing.province();
        if (key.layer().grouped() && key.layer().areaId(province) != null) continue;
        graphics.setColor(fill(key, province));
        graphics.fill(drawing.path());
        if (!key.layer().grouped()) {
          graphics.setColor(province.kind().equals("land") ? LAND_BORDER : WATER_BORDER);
          graphics.draw(drawing.path());
        }
      }
      if (key.layer().grouped()) {
        var areas = areaDrawings(key.layer(), viewport);
        for (var area : areas) {
          graphics.setColor(area.color());
          graphics.fill(area.path());
        }
        graphics.setColor(LAND_BORDER);
        areas.forEach(area -> graphics.draw(area.path()));
      }
      graphics.setColor(RIVER);
      for (Object candidate : rivers.query(viewport)) {
        var river = (RiverDrawing) candidate;
        double widthInPixels = .9 + .11 * (river.width() - 3);
        graphics.setStroke(new BasicStroke((float) (widthInPixels / scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(river.path());
      }
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var stream = new MemoryCacheImageOutputStream(bytes)) {
      if (!ImageIO.write(image, "png", stream)) throw new IOException("JDK PNG 编码器不可用");
    }
    return bytes.toByteArray();
  }

  @SuppressWarnings("unchecked")
  private List<Drawing> drawings(Envelope viewport) {
    List<Drawing> candidates = index.query(viewport);
    candidates.sort(Comparator.comparing(drawing -> drawing.province().id()));
    return candidates;
  }

  @SuppressWarnings("unchecked")
  private List<AreaDrawing> areaDrawings(Layer layer, Envelope viewport) {
    List<AreaDrawing> candidates = areaLayers.computeIfAbsent(layer, this::buildAreas).query(viewport);
    candidates.sort(Comparator.comparing(AreaDrawing::id));
    return candidates;
  }

  private STRtree buildAreas(Layer layer) {
    var groups = new LinkedHashMap<String, List<String>>();
    if (layer == Layer.STATE) map.states().forEach((id, state) -> groups.put(id, state.provinceIds()));
    else map.strategicRegions().forEach((id, region) -> groups.put(id, region.provinceIds()));

    var neighbors = new HashMap<String, Set<String>>();
    for (var edge : map.adjacencies()) {
      if (edge.borderLength() <= 0 || edge.kind().startsWith("special")) continue;
      String a = layer.areaId(map.provinces().get(edge.a()));
      String b = layer.areaId(map.provinces().get(edge.b()));
      if (a == null || b == null || a.equals(b)) continue;
      neighbors.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
      neighbors.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
    }

    var slots = new HashMap<String, Integer>();
    var areaIndex = new STRtree();
    for (var group : groups.entrySet()) {
      if (group.getValue().isEmpty()) continue;
      String id = group.getKey();
      Path2D path = path(area(layer, id).shape());

      var used = new HashSet<Integer>();
      for (String neighbor : neighbors.getOrDefault(id, Set.of())) used.add(slots.get(neighbor));
      int size = AREA_COLORS.size();
      int slot = Math.floorMod(id.hashCode(), size);
      for (int attempt = 1; used.contains(slot); attempt++) {
        slot = attempt < size ? (slot + 1) % size : attempt;
      }
      slots.put(id, slot);
      Color color = AREA_COLORS.get(slot % size);
      boolean sea = group.getValue().stream().allMatch(member -> map.provinces().get(member).kind().equals("sea"));
      int shades = slot / size + (sea ? 1 : 0);
      for (int i = 0; i < shades; i++) color = color.darker();
      areaIndex.insert(envelope(path), new AreaDrawing(id, path, color));
    }
    areaIndex.build();
    return areaIndex;
  }

  private static ProvinceShape shape(Geometry geometry) {
    var parts = new ArrayList<PolygonPart>();
    for (int i = 0; i < geometry.getNumGeometries(); i++) {
      var polygon = (Polygon) geometry.getGeometryN(i);
      var holes = new ArrayList<Ring>();
      for (int j = 0; j < polygon.getNumInteriorRing(); j++) holes.add(ring(polygon.getInteriorRingN(j)));
      parts.add(new PolygonPart(ring(polygon.getExteriorRing()), holes));
    }
    return new ProvinceShape(parts);
  }

  private static Ring ring(LineString line) {
    return new Ring(Arrays.stream(line.getCoordinates()).map(point -> new Vec2(point.x, point.y)).toList());
  }

  private static int pixels(double extent, double scale) {
    return Math.max(1, (int) Math.ceil(extent * scale));
  }

  private static Envelope envelope(Path2D path) {
    var bounds = path.getBounds2D();
    return new Envelope(bounds.getMinX(), bounds.getMaxX(), bounds.getMinY(), bounds.getMaxY());
  }

  private static Path2D path(ProvinceShape shape) {
    Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
    for (var part : shape.parts()) {
      append(path, part.outer().points());
      path.closePath();
      for (var hole : part.holes()) {
        append(path, hole.points());
        path.closePath();
      }
    }
    return path;
  }

  private static void append(Path2D path, List<Vec2> points) {
    Vec2 first = points.getFirst();
    path.moveTo(first.x(), first.y());
    for (int i = 1; i < points.size(); i++) {
      Vec2 point = points.get(i);
      path.lineTo(point.x(), point.y());
    }
  }

  private static Color fill(ImageKey key, Province province) {
    Layer layer = key.layer();
    if (layer == Layer.OWNER_GAPS && key.gaps().contains(province.id())) return MISSING_OWNER;
    if (key.politics() != null) {
      var territory = key.politics().territories().get(province.id());
      var assignment = territory == null ? null : layer == Layer.CONTROLLER ? territory.controller() : territory.owner();
      if (assignment != null) {
        var country = key.politics().countries().get(assignment.countryId());
        return country.color() == null ? groupColor(country.id()) : Color.decode(country.color());
      }
    }
    if (province.kind().equals("sea")) return SEA;
    if (province.kind().equals("lake")) return LAKE;
    return TERRAIN.getOrDefault(province.terrain(), LAND);
  }

  private static Color groupColor(String id) {
    float hue = Math.floorMod(id.hashCode() * 0x9e3779b9, 360) / 360f;
    return Color.getHSBColor(hue, .27f, .8f);
  }
}
