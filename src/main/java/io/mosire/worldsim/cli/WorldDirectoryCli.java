package io.mosire.worldsim.cli;

import io.mosire.worldsim.app.WorldApplicationService;
import io.mosire.worldsim.app.WorldRuntime;
import io.mosire.worldsim.importer.Hoi4CommunityMapImporter;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import io.mosire.worldsim.world.DurableFiles;
import io.mosire.worldsim.world.WorldDirectory;
import io.mosire.worldsim.world.WorldMigration;
import io.mosire.worldsim.world.WorldView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public jar entry points always use the world directory in the process working directory. */
public final class WorldDirectoryCli {
  private WorldDirectoryCli() {}

  public static int run(String[] args) {
    Path world = Path.of("world").toAbsolutePath().normalize();
    try {
      if (args.length == 0) {
        WorldRuntime.run(world, System.in, System.out);
        return 0;
      }
      if (args[0].equals("help") || args[0].equals("--help")) { usage(); return 0; }
      Map<String, String> options = options(args);
      return switch (args[0]) {
        case "mcp" -> {
          only(options, "--viewer-port", "--decision-turn", "--decision-country", "--decision-maker");
          Integer port = options.containsKey("--viewer-port") ? port(options.get("--viewer-port")) : null;
          WorldRuntime.run(world, System.in, System.out, port, options.get("--decision-turn"), options.get("--decision-country"), options.get("--decision-maker"));
          yield 0;
        }
        case "decision-viewer" -> {
          only(options, "--port", "--decision-turn", "--decision-maker");
          WorldRuntime.viewDecision(world, port(options.getOrDefault("--port", "8765")),
              options.get("--decision-turn"), options.get("--decision-maker"));
          yield 0;
        }
        case "viewer" -> {
          only(options, "--port");
          WorldRuntime.view(world, port(options.getOrDefault("--port", "8765")));
          yield 0;
        }
        case "migrate-sqlite" -> migrate(world, options);
        case "init-hoi4" -> initHoi4(world, options);
        case "verify", "tree", "resolve", "view", "export" -> readWorld(world, args[0], options);
        default -> throw new IllegalArgumentException("未知命令: " + args[0]);
      };
    } catch (TimelineException e) {
      System.err.println(e.code() + ": " + e.getMessage());
      return 1;
    } catch (IllegalArgumentException e) {
      System.err.println("ARGUMENT_ERROR: " + e.getMessage());
      return 2;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("WORLD_INTERRUPTED: 服务已中断");
      return 1;
    }
  }

  private static int migrate(Path world, Map<String, String> options) {
    only(options, "--source");
    Path source = Path.of(options.getOrDefault("--source", "world/imports/legacy.db"));
    var report = WorldMigration.migrateSqlite(world, source);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("migration", report);
    try (FileTimelineStore store = FileTimelineStore.open(world)) {
      var service = new TimelineService(store);
      var ref = store.head(store.genesis().orElseThrow().ref().branch()).orElseThrow();
      try { result.put("view", WorldView.refresh(store, ref, service.load(ref))); }
      catch (RuntimeException e) { result.put("viewWarning", e.getMessage()); }
    }
    System.out.println(JsonCodec.prettyJson(result));
    return 0;
  }

  private static int initHoi4(Path world, Map<String, String> options) {
    only(options, "--source");
    String source = options.get("--source");
    if (source == null) throw new IllegalArgumentException("缺少 --source；目录必须在 world/imports 下");
    try (WorldDirectory directory = WorldDirectory.open(world, true)) {
      if (Files.exists(directory.resolve("world.json"))) throw new TimelineException("WORLD_ALREADY_INITIALIZED", "世界已经存在");
      Path importDir = directory.requireImportDirectory(Path.of(source));
      var imported = new Hoi4CommunityMapImporter().importWorld(importDir);
      try (FileTimelineStore store = FileTimelineStore.createLocked(directory, imported.state().id())) {
        writeReport(directory, "import-report.json", Map.of(
            "source", directory.root().relativize(importDir).toString(), "worldId", imported.state().id(),
            "stats", imported.stats(), "warnings", imported.warnings()));
        writeReport(directory, "provenance.json", importProvenance(directory, importDir, imported));
        TimelineService service = new TimelineService(store);
        var genesis = service.createGenesis(imported.state(), "gm:import-hoi4");
        var output = new LinkedHashMap<String, Object>();
        output.put("genesis", genesis.ref().label()); output.put("stateHash", genesis.stateHash());
        output.put("stats", imported.stats()); output.put("warnings", imported.warnings());
        try { output.put("view", WorldView.refresh(store, genesis.ref(), imported.state())); }
        catch (RuntimeException e) { output.put("viewWarning", e.getMessage()); }
        System.out.println(JsonCodec.prettyJson(output));
      }
    }
    return 0;
  }

  private static void writeReport(WorldDirectory directory, String filename, Object report) {
    String temp = ".staging/report-" + UUID.randomUUID() + ".json";
    DurableFiles.writeNew(directory.resolve(temp), JsonCodec.prettyJson(report));
    DurableFiles.moveAtomic(directory.resolve(temp), directory.resolve(filename), true);
  }

  private static Map<String, Object> importProvenance(WorldDirectory world, Path source,
      Hoi4CommunityMapImporter.ImportResult imported) {
    Map<String, Object> origin = new LinkedHashMap<>();
    origin.put("name", source.getFileName().toString());
    origin.put("path", world.root().relativize(source).toString());
    Path sourceInfo = source.resolve(".worldsim-source.txt");
    try {
      if (Files.isRegularFile(sourceInfo) && Files.size(sourceInfo) <= 4096) {
        for (String line : Files.readAllLines(sourceInfo)) {
          if (line.startsWith("repo=")) origin.put("repository", line.substring(5));
          if (line.startsWith("commit=")) origin.put("commit", line.substring(7));
        }
      }
    } catch (IOException e) {
      origin.put("metadataWarning", "无法读取来源描述: " + e.getMessage());
    }
    List<Map<String, Object>> evidence = new ArrayList<>();
    if (!imported.inferredSeaProvinceIds().isEmpty()) {
      Map<String, Object> sea = new LinkedHashMap<>();
      sea.put("entityType", "province"); sea.put("ids", imported.inferredSeaProvinceIds().stream().map(String::valueOf).toList());
      sea.put("field", "kind"); sea.put("value", "sea"); sea.put("source", "heightmap inference over source definitions");
      sea.put("status", "inferred"); sea.put("confidence", null);
      evidence.add(sea);
    }
    if (!imported.repairedPolygonProvinceIds().isEmpty()) {
      Map<String, Object> shape = new LinkedHashMap<>();
      shape.put("entityType", "province"); shape.put("ids", imported.repairedPolygonProvinceIds().stream().map(String::valueOf).toList());
      shape.put("field", "shape"); shape.put("source", "JTS GeometryFixer during polygon import");
      shape.put("status", "repaired"); shape.put("confidence", null);
      evidence.add(shape);
    }
    Map<String, Object> repair = new LinkedHashMap<>();
    repair.put("count", imported.repairedPolygonProvinceIds().size());
    repair.put("provinceIds", imported.repairedPolygonProvinceIds().stream().map(String::valueOf).toList());
    repair.put("source", "JTS GeometryFixer during polygon import"); repair.put("status", "repaired"); repair.put("confidence", null);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("formatVersion", 1); result.put("kind", "import-evidence"); result.put("worldId", imported.state().id());
    result.put("node", "main-0"); result.put("stateHash", imported.state().contentHash()); result.put("source", origin);
    result.put("fieldEvidence", evidence); result.put("geometryRepair", repair);
    result.put("note", "Informational provenance for the specified node; not a replacement for timeline state.");
    return result;
  }

  private static int readWorld(Path world, String command, Map<String, String> options) {
    try (FileTimelineStore store = FileTimelineStore.open(world)) {
      WorldApplicationService app = new WorldApplicationService(store);
      Object result = switch (command) {
        case "verify" -> { only(options); yield Map.of("verifiedNodes", new TimelineService(store).verifyAll(), "result", "PASS"); }
        case "tree" -> { only(options); yield app.tree(0, 200); }
        case "resolve" -> { only(options, "--address"); yield app.lookup(required(options, "--address"), false); }
        case "view" -> { only(options, "--node"); yield app.refreshView(required(options, "--node")); }
        case "export" -> { only(options); yield app.exportWorld(); }
        default -> throw new IllegalArgumentException("未知命令: " + command);
      };
      System.out.println(JsonCodec.prettyJson(result));
      return 0;
    }
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].startsWith("--") || i + 1 >= args.length || args[i + 1].startsWith("--")) {
        throw new IllegalArgumentException("参数需要值: " + args[i]);
      }
      String key = args[i];
      if (result.putIfAbsent(key, args[++i]) != null) throw new IllegalArgumentException("重复参数: " + key);
    }
    return result;
  }

  private static void only(Map<String, String> options, String... allowed) {
    Set<String> names = Set.of(allowed);
    for (String key : options.keySet()) if (!names.contains(key)) throw new IllegalArgumentException("未知参数: " + key);
  }

  private static String required(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数 " + key);
    return value;
  }

  private static int port(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port >= 0 && port <= 65535) return port;
    } catch (NumberFormatException ignored) {
      // Report the same range for malformed and out-of-range ports.
    }
    throw new IllegalArgumentException("查看器端口须为 0 到 65535 的整数");
  }

  private static void usage() {
    System.out.println("""
        WorldSimulator（工作目录下的 world/）
          无参数 | mcp [--viewer-port <port>]  启动 GM stdio MCP，可同时开启查看器
          mcp --decision-turn <node/turn/id> --decision-maker <id>
                                               启动绑定身份的决策 MCP
          mcp --decision-turn <node/turn/id> --decision-country <id>
                                               启动旧版国别回合的决策 MCP
          viewer [--port 8765]                启动本地只读地图查看器
          migrate-sqlite [--source world/imports/legacy.db]
          init-hoi4 --source world/imports/<source>
          verify | tree | export
          resolve --address <node/map/type/id>
          view --node <branch-tick>
        """);
  }
}
