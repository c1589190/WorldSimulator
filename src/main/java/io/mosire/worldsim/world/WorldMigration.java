package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.PolygonTopology;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.SqliteTimelineStore;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.timeline.TimelineService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** One-time, non-destructive conversion of an SQLite world placed under world/imports. */
public final class WorldMigration {
  private WorldMigration() {}

  public record Report(String source, String worldId, int nodes, String genesis, String genesisHash,
      int provinces, int states, int regions, int hubs, int railways, int adjacencies,
      List<String> inheritedTopologyIssues) {}

  public static Report migrateSqlite(Path worldPath, Path sourcePath) {
    try (WorldDirectory destination = WorldDirectory.open(worldPath, true)) {
      if (Files.exists(destination.resolve("world.json"))) {
        throw new TimelineException("WORLD_ALREADY_INITIALIZED", "world.json 已存在，不能覆盖: " + destination.root());
      }
      Path source = destination.requireImportFile(sourcePath);
      try (SqliteTimelineStore old = SqliteTimelineStore.readOnly(source)) {
        TimelineService original = new TimelineService(old);
        original.verifyAll();
        TimelineNode genesis = old.genesis().orElseThrow(() ->
            new TimelineException("WORLD_IMPORT_EMPTY", "源库没有创世节点"));
        StaticWorldState initial = original.load(genesis.ref());
        Set<String> topology = new TreeSet<>(PolygonTopology.consistencyIssues(initial.map()));
        Map<NodeRef, TimelineNode> ordered = new LinkedHashMap<>();
        for (BranchId branch : old.branches()) {
          NodeRef head = old.head(branch).orElseThrow();
          for (TimelineNode node : old.pathToRoot(head)) ordered.putIfAbsent(node.ref(), node);
        }
        Path stage = destination.resolve(".staging/migration-" + UUID.randomUUID());
        String dataDirectory;
        try (FileTimelineStore fresh = FileTimelineStore.create(stage, initial.id())) {
          for (TimelineNode node : ordered.values()) {
            fresh.saveNode(node, old.checkpoint(node.ref()).orElse(null));
          }
          int verified = new TimelineService(fresh).verifyAll();
          if (verified != ordered.size()) throw new TimelineException("WORLD_MIGRATION_INVALID", "迁移节点数不一致");
          dataDirectory = fresh.dataDirectory();
        }
        var map = initial.map();
        Report report = new Report(destination.root().relativize(source).toString(), initial.id(), ordered.size(),
            genesis.ref().label(), genesis.stateHash(), map.provinces().size(), map.states().size(),
            map.strategicRegions().size(), map.supplyHubs().size(), map.railways().size(),
            map.adjacencies().size(), new ArrayList<>(topology));
        // This report is informational; nodes and their checksums are the world authority.
        DurableFiles.writeNew(stage.resolve("migration-report.json"), JsonCodec.prettyJson(report));
        // Every published path remains relative to world/, so moving a single generation preserves references.
        DurableFiles.moveAtomic(stage.resolve(dataDirectory), destination.resolve(dataDirectory), false);
        DurableFiles.moveAtomic(stage.resolve("migration-report.json"), destination.resolve("migration-report.json"), true);
        DurableFiles.moveAtomic(stage.resolve("world.json"), destination.resolve("world.json"), false);
        return report;
      }
    }
  }
}
