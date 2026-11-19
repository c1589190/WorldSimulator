package io.mosire.worldsim.app.viewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.app.WorldApplicationService;
import io.mosire.worldsim.app.WorldApplicationService.SavedMap;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.timeline.TimelineException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only HTTP adapter for the app that already owns the world session. */
public final class WorldViewerServer implements AutoCloseable {
  private static final String RESOURCE_ROOT = "/io/mosire/worldsim/viewer/";

  private record HubMark(String id, String provinceId, int level, Vec2 point) {}
  private record RailLine(String id, int level, List<Vec2> points) {}

  private final WorldApplicationService app;
  private final HttpServer server;
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final LinkedHashMap<String, ViewerMap> frames = new LinkedHashMap<>(4, .75f, true);

  private WorldViewerServer(WorldApplicationService app, int port) throws IOException {
    this.app = app;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
  }

  public static WorldViewerServer start(WorldApplicationService app, int port) {
    System.setProperty("java.awt.headless", "true");
    try {
      WorldViewerServer viewer = new WorldViewerServer(app, port);
      viewer.server.setExecutor(viewer.workers);
      viewer.server.createContext("/", viewer::handle);
      viewer.server.start();
      System.err.println("地图查看器已启动 http://127.0.0.1:" + viewer.port() + "/");
      return viewer;
    } catch (IOException e) {
      throw new TimelineException("VIEWER_LISTEN_FAILED", "无法启动本地地图查看器: " + e.getMessage(), e);
    }
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override public void close() {
    server.stop(0);
    workers.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      String host = exchange.getRequestHeaders().getFirst("Host");
      if (!validHost(host)) {
        error(exchange, 403, "VIEWER_HOST_DENIED", "请通过本机 localhost 访问地图查看器");
        return;
      }
      if (!exchange.getRequestMethod().equals("GET")) {
        exchange.getResponseHeaders().set("Allow", "GET");
        error(exchange, 405, "VIEWER_READ_ONLY", "查看器只接受 GET 请求");
        return;
      }
      try {
        route(exchange);
      } catch (AddressException e) {
        error(exchange, e.code().equals("MAP_ENTITY_NOT_FOUND") ? 404 : 400, e.code(), e.getMessage());
      } catch (TimelineException e) {
        int status = switch (e.code()) {
          case "TIMELINE_NODE_NOT_FOUND" -> 404;
          case "VIEWER_HASH_MISMATCH" -> 409;
          case "TIMELINE_NODE_INVALID", "ARGUMENT_INVALID" -> 400;
          default -> 500;
        };
        error(exchange, status, e.code(), status == 500 ? "读取已保存地图失败，详见服务日志" : e.getMessage());
        if (status == 500) System.err.println(e.code() + ": " + e.getMessage());
      } catch (IllegalArgumentException e) {
        error(exchange, 400, "VIEWER_ARGUMENT_INVALID", e.getMessage());
      }
    }
  }

  private boolean validHost(String host) {
    return ("localhost:" + port()).equalsIgnoreCase(host) || ("127.0.0.1:" + port()).equals(host)
        || (port() == 80 && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)));
  }

  private void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getRawPath();
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    switch (path) {
      case "/" -> resource(exchange, "index.html", "text/html; charset=utf-8");
      case "/favicon.ico" -> exchange.sendResponseHeaders(204, -1);
      case "/viewer.css" -> resource(exchange, "viewer.css", "text/css; charset=utf-8");
      case "/viewer.js" -> resource(exchange, "viewer.js", "text/javascript; charset=utf-8");
      case "/api/nodes" -> json(exchange, 200, app.tree(
          integer(query.getOrDefault("offset", "0"), "offset"),
          integer(query.getOrDefault("limit", "100"), "limit")));
      case "/api/map" -> json(exchange, 200, info(app.savedMap(query.get("node"))));
      case "/api/overview", "/api/tile" -> render(exchange, query, path.equals("/api/tile"));
      case "/api/province" -> json(exchange, 200, province(snapshot(query), NodeAddress.parse(required(query, "address"))));
      case "/api/pick" -> {
        SavedMap saved = snapshot(query);
        Vec2 point = new Vec2(coordinate(query, "x"), coordinate(query, "y"));
        Province picked = frame(saved).pick(point);
        json(exchange, 200, picked == null ? null : province(saved,
            new NodeAddress(saved.node(), List.of("map", "province", picked.id()))));
      }
      case "/api/logistics" -> json(exchange, 200, logistics(snapshot(query).map()));
      default -> error(exchange, 404, "VIEWER_NOT_FOUND", "查看器路径不存在");
    }
  }

  private SavedMap snapshot(Map<String, String> query) {
    String hash = required(query, "hash");
    SavedMap saved = app.savedMap(required(query, "node"));
    if (!saved.stateHash().equals(hash)) {
      throw new TimelineException("VIEWER_HASH_MISMATCH", "地图版本已过期，请重新加载节点");
    }
    return saved;
  }

  private Map<String, Object> info(SavedMap saved) {
    WorldMap map = saved.map();
    return Map.ofEntries(Map.entry("worldId", app.worldId()), Map.entry("mapName", map.name()),
        Map.entry("node", saved.node().label()), Map.entry("stateHash", saved.stateHash()),
        Map.entry("width", map.width()), Map.entry("height", map.height()),
        Map.entry("overviewScale", ViewerMap.overviewScale(map)), Map.entry("tileSize", ViewerMap.TILE_SIZE),
        Map.entry("maxTileLevel", ViewerMap.MAX_LEVEL), Map.entry("provinces", map.provinces().size()),
        Map.entry("states", map.states().size()), Map.entry("regions", map.strategicRegions().size()),
        Map.entry("supplyHubs", map.supplyHubs().size()), Map.entry("railways", map.railways().size()));
  }

  private Map<String, Object> province(SavedMap saved, NodeAddress address) {
    var path = address.path();
    if (!address.node().equals(saved.node()) || path.size() != 3
        || !path.get(0).equals("map") || !path.get(1).equals("province")) {
      throw new IllegalArgumentException("请输入当前节点下的省份地址");
    }
    Map<String, Object> result = app.lookup(address.canonical(), true);
    Province province = (Province) result.get("entity");
    if (province.stateId() != null) result.put("stateName", saved.map().states().get(province.stateId()).name());
    if (province.regionId() != null) result.put("regionName", saved.map().strategicRegions().get(province.regionId()).name());
    return result;
  }

  private static Map<String, Object> logistics(WorldMap map) {
    var hubs = map.supplyHubs().values().stream().map(hub -> {
      var link = map.entityLinks().get("supplyHub:" + hub.id());
      Vec2 point = link != null && link.displayPoint() != null ? link.displayPoint()
          : map.provinces().get(hub.provinceId()).displayPoint();
      return new HubMark(hub.id(), hub.provinceId(), hub.level(), point);
    }).toList();
    var railways = map.railways().values().stream().map(railway -> new RailLine(railway.id(), railway.level(),
        railway.provincePath().stream().map(id -> map.provinces().get(id).displayPoint()).toList())).toList();
    return Map.of("hubs", hubs, "railways", railways);
  }

  private void render(HttpExchange exchange, Map<String, String> query, boolean tile) throws IOException {
    SavedMap saved = snapshot(query);
    ViewerMap.Layer layer = ViewerMap.Layer.parse(required(query, "layer"));
    int level = tile ? integer(required(query, "level"), "level") : 0;
    int x = tile ? integer(required(query, "x"), "x") : 0;
    int y = tile ? integer(required(query, "y"), "y") : 0;
    if (tile && !ViewerMap.validTile(saved.map(), level, x, y)) {
      throw new IllegalArgumentException("瓦片超出地图范围或缩放级别");
    }
    byte[] png = frame(saved).image(layer, level, x, y);
    exchange.getResponseHeaders().set("Cache-Control", "private, max-age=300");
    send(exchange, 200, "image/png", png);
  }

  private synchronized ViewerMap frame(SavedMap saved) {
    ViewerMap frame = frames.computeIfAbsent(saved.stateHash(), hash -> new ViewerMap(saved.map()));
    if (frames.size() > 2) frames.pollFirstEntry();
    return frame;
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new HashMap<>();
    if (raw == null || raw.isEmpty()) return result;
    if (raw.length() > 8192) throw new IllegalArgumentException("查询参数过长");
    for (String field : raw.split("&", -1)) {
      int eq = field.indexOf('=');
      if (eq <= 0) throw new IllegalArgumentException("查询参数格式无效");
      String key = URLDecoder.decode(field.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(field.substring(eq + 1), StandardCharsets.UTF_8);
      if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复查询参数: " + key);
    }
    return result;
  }

  private static String required(Map<String, String> query, String key) {
    String value = query.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少查询参数: " + key);
    return value;
  }

  private static int integer(String value, String key) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " 必须是整数");
    }
  }

  private static double coordinate(Map<String, String> query, String key) {
    double value = Double.parseDouble(required(query, key));
    if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " 必须是有限坐标");
    return value;
  }

  private static void resource(HttpExchange exchange, String name, String type) throws IOException {
    try (var input = WorldViewerServer.class.getResourceAsStream(RESOURCE_ROOT + name)) {
      if (input == null) throw new IOException("查看器资源缺失: " + name);
      send(exchange, 200, type, input.readAllBytes());
    }
  }

  private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
    json(exchange, status, Map.of("code", code, "message", message == null ? code : message));
  }

  private static void json(HttpExchange exchange, int status, Object value) throws IOException {
    send(exchange, status, "application/json; charset=utf-8", JsonCodec.mapper().writeValueAsBytes(value));
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
