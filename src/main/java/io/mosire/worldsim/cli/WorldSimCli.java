package io.mosire.worldsim.cli;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.importer.Hoi4CommunityMapImporter;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.WorldMapValidator;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.SqliteTimelineStore;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.timeline.TimelineWorkingCopy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Headless core 最小 CLI：demo / replay / verify / tree。 */
public final class WorldSimCli {
  private WorldSimCli() {}

  public static void main(String[] args) {
    int exit = run(args);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  public static int run(String[] args) {
    if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
      usage();
      return args.length == 0 ? 2 : 0;
    }
    try {
      return switch (args[0]) {
        case "demo" -> demo(options(args, 1));
        case "import-hoi4" -> importHoi4(options(args, 1));
        case "benchmark" -> benchmark(options(args, 1));
        case "resolve" -> resolve(options(args, 1));
        case "replay" -> replay(options(args, 1));
        case "verify" -> verify(options(args, 1));
        case "tree" -> tree(options(args, 1));
        default -> {
          System.err.println("未知命令: " + args[0]);
          usage();
          yield 2;
        }
      };
    } catch (TimelineException e) {
      System.err.println("TIMELINE_ERROR " + e.code() + ": " + e.getMessage());
      return 1;
    } catch (IllegalArgumentException e) {
      System.err.println("ARGUMENT_ERROR: " + e.getMessage());
      return 2;
    }
  }

  private static int demo(Map<String, String> options) {
    Path db = Path.of(options.getOrDefault("--db", "runtime/headless-demo.db"));
    if (!prepareFreshDatabase(db, options.containsKey("--reset"))) {
      return 1;
    }

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store, 2);
      StaticWorldState initial = SampleWorld.create();
      requireValid(initial);

      TimelineNode genesis = service.createGenesis(initial, "cli:demo");

      TimelineWorkingCopy advance = service.openNext(genesis.ref());
      service.apply(advance, new WorldChange.PutProvince(
          SampleWorld.renameProvince(initial.map(), SampleWorld.EAST, "East draft")));
      service.apply(advance, new WorldChange.PutProvince(
          SampleWorld.renameProvince(initial.map(), SampleWorld.EAST, "East revised")));
      int mergedChanges = advance.pendingChangeCount();
      TimelineNode main1 = service.save(advance, 1, "gm:demo");
      if (mergedChanges != 2) {
        throw new IllegalStateException("同一 working copy 的两次写入未合并: " + mergedChanges);
      }

      TimelineWorkingCopy discard = service.openNext(main1.ref());
      service.apply(discard, new WorldChange.PutProvince(
          SampleWorld.renameProvince(service.load(main1.ref()).map(), SampleWorld.WEST, "DISCARDED")));
      String beforeDiscard = main1.stateHash();
      String dirtyHash = discard.currentState().contentHash();
      service.discard(discard);
      String afterDiscard = service.load(main1.ref()).contentHash();
      if (!beforeDiscard.equals(afterDiscard) || discard.isOpen() || !discard.pendingChanges().isEmpty()) {
        throw new IllegalStateException("discard 未回到保存快照");
      }

      TimelineWorkingCopy advance2 = service.openNext(main1.ref());
      service.apply(advance2, new WorldChange.PutProvince(
          SampleWorld.renameProvince(service.load(main1.ref()).map(), SampleWorld.WEST, "West revised")));
      TimelineNode main2 = service.save(advance2, 2, "gm:demo");

      boolean historyRejected = false;
      try {
        service.openNext(main1.ref());
      } catch (TimelineException e) {
        historyRejected = e.code().equals("TIMELINE_FORK_REQUIRED");
      }

      TimelineWorkingCopy fork = service.openFork(main1.ref(), new BranchId("tree2"));
      service.apply(fork, new WorldChange.PutProvince(
          SampleWorld.renameProvince(service.load(main1.ref()).map(), SampleWorld.EAST, "East fork")));
      TimelineNode forkNode = service.save(fork, 2, "gm:demo");

      StaticWorldState main2State = service.load(main2.ref());
      StaticWorldState forkState = service.load(forkNode.ref());
      if (!"East revised".equals(main2State.map().provinces().get(SampleWorld.EAST).name())
          || !"West revised".equals(main2State.map().provinces().get(SampleWorld.WEST).name())) {
        throw new IllegalStateException("main 分支状态不符合预期");
      }
      if (!"East fork".equals(forkState.map().provinces().get(SampleWorld.EAST).name())
          || !"West".equals(forkState.map().provinces().get(SampleWorld.WEST).name())) {
        throw new IllegalStateException("fork 分支状态不符合预期或受到 main 后续影响");
      }

      int verified = service.verifyAll();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("database", db.toAbsolutePath().toString());
      report.put("genesis", genesis.ref().toString());
      report.put("nodes", List.of(
          nodeSummary(genesis),
          nodeSummary(main1),
          nodeSummary(main2),
          nodeSummary(forkNode)));
      report.put("mergedChangesInMain1", mergedChanges);
      report.put("discardRestoredHash", beforeDiscard.equals(afterDiscard));
      report.put("dirtyHashDiscarded", dirtyHash);
      report.put("historicalAdvanceRejected", historyRejected);
      report.put("forkIndependent", true);
      report.put("verifiedNodes", verified);
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static int importHoi4(Map<String, String> options) {
    Path source = Path.of(required(options, "--source")).toAbsolutePath().normalize();
    Path db = Path.of(options.getOrDefault("--db", "runtime/worlds/community-map.db"));
    if (!prepareFreshDatabase(db, options.containsKey("--reset"))) {
      return 1;
    }
    String savedBy = options.getOrDefault("--saved-by", "gm:import-hoi4");

    Hoi4CommunityMapImporter importer = new Hoi4CommunityMapImporter();
    long importStart = System.nanoTime();
    Hoi4CommunityMapImporter.ImportResult imported = importer.importWorld(source);
    long importedAt = System.nanoTime();

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      TimelineNode genesis = service.createGenesis(imported.state(), savedBy);
      long savedAt = System.nanoTime();
      int verified = service.verifyAll();
      long verifiedAt = System.nanoTime();

      Map<String, Object> report = new LinkedHashMap<>();
      report.put("source", source.toString());
      report.put("database", db.toAbsolutePath().toString());
      report.put("genesis", genesis.ref().toString());
      report.put("head", service.store().head(new BranchId("main")).orElseThrow().toString());
      report.put("stateHash", imported.state().contentHash());
      report.put("stats", imported.stats());
      report.put("warnings", imported.warnings());
      report.put("timingsMillis", Map.of(
          "import", millis(importedAt - importStart),
          "genesisSave", millis(savedAt - importedAt),
          "verifyAll", millis(verifiedAt - savedAt)));
      report.put("verifiedNodes", verified);
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static int benchmark(Map<String, String> options) {
    Path db = Path.of(options.getOrDefault("--db", "runtime/benchmark.db"));
    boolean reset = options.containsKey("--reset");
    if (!prepareFreshDatabase(db, reset)) {
      return 1;
    }
    int columns = integerOption(options, "--columns", 20);
    int rows = integerOption(options, "--rows", 15);
    double cellSize = doubleOption(options, "--cell-size", SyntheticWorld.DEFAULT_CELL_SIZE);

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      long start = System.nanoTime();
      StaticWorldState state = SyntheticWorld.create(columns, rows, cellSize);
      long generatedAt = System.nanoTime();

      var issues = WorldMapValidator.validate(state.map());
      long validatedAt = System.nanoTime();
      if (!issues.isEmpty()) {
        var first = issues.get(0);
        throw new TimelineException("TIMELINE_STATE_INVALID",
            "合成世界校验失败: " + first.code() + " " + first.message());
      }

      TimelineNode genesis = service.createGenesis(state, "benchmark");
      long genesisAt = System.nanoTime();

      String firstProvince = "p-0-0";
      TimelineWorkingCopy workingCopy = service.openNext(genesis.ref());
      service.apply(workingCopy, new WorldChange.PutProvince(
          SampleWorld.renameProvince(state.map(), firstProvince, "Benchmark revised")));
      TimelineNode main1 = service.save(workingCopy, 1, "benchmark");
      long deltaAt = System.nanoTime();

      StaticWorldState replayed = service.load(main1.ref());
      long replayAt = System.nanoTime();

      Map<String, Object> report = new LinkedHashMap<>();
      report.put("database", db.toAbsolutePath().toString());
      report.put("columns", columns);
      report.put("rows", rows);
      report.put("provinces", state.map().provinces().size());
      report.put("states", state.map().states().size());
      report.put("regions", state.map().strategicRegions().size());
      report.put("adjacencies", state.map().adjacencies().size());
      report.put("validationIssues", issues.size());
      report.put("genesis", genesis.ref().toString());
      report.put("head", service.store().head(new BranchId("main")).orElseThrow().toString());
      report.put("replayedProvince", replayed.map().provinces().get(firstProvince).name());
      report.put("stateHash", replayed.contentHash());
      report.put("timingsMillis", Map.of(
          "generate", millis(generatedAt - start),
          "validate", millis(validatedAt - generatedAt),
          "genesisSave", millis(genesisAt - validatedAt),
          "deltaSave", millis(deltaAt - genesisAt),
          "replay", millis(replayAt - deltaAt)));
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static long millis(long nanos) {
    return Math.round(nanos / 1_000_000.0);
  }

  private static int integerOption(Map<String, String> options, String key, int defaultValue) {
    String value = options.get(key);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " 必须是整数: " + value);
    }
  }

  private static double doubleOption(Map<String, String> options, String key, double defaultValue) {
    String value = options.get(key);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " 必须是数字: " + value);
    }
  }

  private static int resolve(Map<String, String> options) {
    Path db = requiredDb(options);
    NodeAddress address = NodeAddress.parse(required(options, "--address"));
    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      StaticWorldState state = service.load(address.node());
      MapAddressResolver.ResolvedEntity resolved = MapAddressResolver.resolve(state.map(), address);

      Map<String, Object> report = new LinkedHashMap<>();
      report.put("address", address.canonical());
      report.put("node", address.node().toString());
      report.put("kind", resolved.kind());
      report.put("stableId", resolved.stableId());
      report.put("displayName", resolved.displayName());
      report.put("valueType", resolved.value().getClass().getSimpleName());
      report.put("detail", detail(resolved));
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static Map<String, Object> detail(MapAddressResolver.ResolvedEntity resolved) {
    Map<String, Object> detail = new LinkedHashMap<>();
    switch (resolved.value()) {
      case Province province -> {
        detail.put("provinceKind", province.kind());
        detail.put("terrain", province.terrain());
        detail.put("stateId", province.stateId());
        detail.put("regionId", province.regionId());
        detail.put("polygonParts", province.shape().parts().size());
      }
      case State state -> detail.put("provinceCount", state.provinceIds().size());
      case StrategicRegion region -> detail.put("provinceCount", region.provinceIds().size());
      case SupplyHub hub -> {
        detail.put("provinceId", hub.provinceId());
        detail.put("level", hub.level());
      }
      case Railway railway -> {
        detail.put("level", railway.level());
        detail.put("provinceCount", railway.provincePath().size());
      }
      default -> detail.put("valueClass", resolved.value().getClass().getName());
    }
    return detail;
  }

  private static int replay(Map<String, String> options) {
    Path db = requiredDb(options);
    NodeRef ref = parseNode(required(options, "--node"));
    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      StaticWorldState state = service.replay(ref);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("node", ref.toString());
      report.put("stateId", state.id());
      report.put("stateHash", state.contentHash());
      report.put("provinces", state.map().provinces().size());
      report.put("validated", true);
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static int verify(Map<String, String> options) {
    Path db = requiredDb(options);
    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      int count = service.verifyAll();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("verifiedNodes", count);
      report.put("result", "PASS");
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static int tree(Map<String, String> options) {
    Path db = requiredDb(options);
    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      Map<String, Object> report = new LinkedHashMap<>();
      for (BranchId branch : store.branches()) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TimelineNode node : store.nodes(branch)) {
          nodes.add(nodeSummary(node));
        }
        report.put(branch.value(), Map.of(
            "head", store.head(branch).orElseThrow().toString(),
            "nodes", nodes));
      }
      System.out.println(JsonCodec.prettyJson(report));
      return 0;
    }
  }

  private static Map<String, Object> nodeSummary(TimelineNode node) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("ref", node.ref().toString());
    summary.put("parent", node.parent() == null ? null : node.parent().toString());
    summary.put("stateHash", node.stateHash());
    summary.put("savedBy", node.savedBy());
    summary.put("savedAtEpochMilli", node.savedAtEpochMilli());
    return summary;
  }

  private static void requireValid(StaticWorldState state) {
    var issues = WorldMapValidator.validate(state.map());
    if (!issues.isEmpty()) {
      throw new TimelineException("TIMELINE_STATE_INVALID",
          "示例世界校验失败: " + issues.get(0).code() + " " + issues.get(0).message());
    }
  }

  private static boolean prepareFreshDatabase(Path db, boolean reset) {
    if (reset) {
      deleteDatabase(db);
    } else if (Files.exists(db)) {
      System.err.println("数据库已存在: " + db.toAbsolutePath());
      System.err.println("需要新库；重跑请加 --reset 或换 --db 路径。");
      return false;
    }
    return true;
  }

  private static Path requiredDb(Map<String, String> options) {
    return Path.of(required(options, "--db"));
  }

  private static String required(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static NodeRef parseNode(String text) {
    NodeAddress address = NodeAddress.parse(text);
    if (!address.path().isEmpty()) {
      throw new IllegalArgumentException("--node 只接受节点地址，不接受实体路径: " + text);
    }
    return address.node();
  }

  private static Map<String, String> options(String[] args, int start) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = start; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--reset")) {
        result.put(arg, "true");
        continue;
      }
      if (!arg.startsWith("--")) {
        throw new IllegalArgumentException("未知参数: " + arg);
      }
      if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
        throw new IllegalArgumentException("参数缺少值: " + arg);
      }
      result.put(arg, args[++i]);
    }
    return result;
  }

  private static void deleteDatabase(Path db) {
    try {
      Files.deleteIfExists(db);
      Files.deleteIfExists(Path.of(db + "-wal"));
      Files.deleteIfExists(Path.of(db + "-shm"));
    } catch (IOException e) {
      throw new IllegalArgumentException("无法删除旧数据库: " + db.toAbsolutePath(), e);
    }
  }

  private static void usage() {
    System.out.println("""
        WorldSimulator headless core CLI
          demo        [--db <path>] [--reset]      完整 genesis/apply/discard/save/fork/replay 演示
          import-hoi4 --source <dir> [--db <path>] [--reset] [--saved-by <id>]
                                                   导入下载的 HOI4 社区地图为原生 polygon 世界
          benchmark [--db <path>] [--reset] [--columns N] [--rows N] [--cell-size N]
                                                     生成合成 polygon 世界并测量校验/保存/回放
          resolve   --db <path> --address <node-address>
                                                     解析 map/province|state|region|supplyHub 地址
          replay    --db <path> --node <branch-tick> 重放并校验节点
          verify    --db <path>                      逐节点重放校验
          tree      --db <path>                      输出分支/节点树
        """);
  }
}
