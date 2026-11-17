package io.mosire.worldsim.cli;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
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

  static int run(String[] args) {
    if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
      usage();
      return args.length == 0 ? 2 : 0;
    }
    try {
      return switch (args[0]) {
        case "demo" -> demo(options(args, 1));
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
    boolean reset = options.containsKey("--reset");
    if (reset) {
      deleteDatabase(db);
    } else if (Files.exists(db)) {
      System.err.println("数据库已存在: " + db.toAbsolutePath());
      System.err.println("演示需要新库；重跑请加 --reset 或换 --db 路径。");
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
          demo   [--db <path>] [--reset]        跑完整 genesis/apply/discard/save/fork/replay 演示
          replay --db <path> --node <branch-tick> 重放并校验节点
          verify --db <path>                      逐节点重放校验
          tree   --db <path>                      输出分支/节点树
        """);
  }
}
