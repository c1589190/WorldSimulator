package io.mosire.worldsim.app.viewer;

import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.JtsGeometry;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Ring;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.map.WorldMap;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

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
import java.util.Comparator;
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
  }

  private record Drawing(Province province, Path2D path) {}
  private record ImageKey(Layer layer, int level, int x, int y, CountryState politics, Set<String> gaps) {}

  private final WorldMap map;
  private final STRtree index = new STRtree();
  private final LinkedHashMap<ImageKey, byte[]> images = new LinkedHashMap<>(16, .75f, true);
  private long imageBytes;

  ViewerMap(WorldMap map) {
    this.map = map;
    for (Province province : map.provinces().values()) {
      Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
      for (var part : province.shape().parts()) {
        append(path, part.outer());
        part.holes().forEach(hole -> append(path, hole));
      }
      var bounds = path.getBounds2D();
      index.insert(new Envelope(bounds.getMinX(), bounds.getMaxX(), bounds.getMinY(), bounds.getMaxY()),
          new Drawing(province, path));
    }
    index.build();
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
        graphics.setColor(fill(key, province));
        graphics.fill(drawing.path());
        graphics.setColor(province.kind().equals("land") ? LAND_BORDER : WATER_BORDER);
        graphics.draw(drawing.path());
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

  private static int pixels(double extent, double scale) {
    return Math.max(1, (int) Math.ceil(extent * scale));
  }

  private static void append(Path2D path, Ring ring) {
    Vec2 first = ring.points().getFirst();
    path.moveTo(first.x(), first.y());
    for (int i = 1; i < ring.points().size(); i++) {
      Vec2 point = ring.points().get(i);
      path.lineTo(point.x(), point.y());
    }
    path.closePath();
  }

  private static Color fill(ImageKey key, Province province) {
    Layer layer = key.layer();
    if (layer == Layer.OWNER_GAPS && key.gaps().contains(province.id())) return MISSING_OWNER;
    if (key.politics() != null) {
      var territory = key.politics().territories().get(province.id());
      var assignment = territory == null ? null : layer == Layer.CONTROLLER ? territory.controller() : territory.owner();
      if (assignment != null) {
        var country = key.politics().countries().get(assignment.countryId());
        return country.color() == null ? groupColor(country.id(), false) : Color.decode(country.color());
      }
    }
    if (layer == Layer.REGION && province.regionId() != null) {
      return groupColor(province.regionId(), province.kind().equals("sea"));
    }
    if (province.kind().equals("sea")) return SEA;
    if (province.kind().equals("lake")) return LAKE;
    if (layer == Layer.STATE && province.stateId() != null) return groupColor(province.stateId(), false);
    return TERRAIN.getOrDefault(province.terrain(), LAND);
  }

  private static Color groupColor(String id, boolean water) {
    float hue = Math.floorMod(id.hashCode() * 0x9e3779b9, 360) / 360f;
    return Color.getHSBColor(hue, water ? .44f : .27f, water ? .43f : .8f);
  }
}
