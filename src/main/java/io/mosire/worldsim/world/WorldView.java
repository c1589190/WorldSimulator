package io.mosire.worldsim.world;

import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A replaceable, explicitly derived, human-readable view of one saved node. */
public final class WorldView {
  private WorldView() {}

  public record Pointer(String node, String stateHash, String directory) {}

  public static Pointer refresh(FileTimelineStore store, NodeRef node, StaticWorldState state) {
    WorldDirectory world = store.world();
    if (!store.find(node).orElseThrow().stateHash().equals(state.contentHash())) {
      throw new TimelineException("WORLD_VIEW_HASH_MISMATCH", "视图状态与保存节点不一致: " + node);
    }
    SnapshotFiles snapshots = new SnapshotFiles(world, store.dataDirectory());
    SnapshotFiles.Manifest snapshot = snapshots.readManifest(snapshots.write(state));
    String version = UUID.randomUUID().toString();
    String directory = "view/versions/" + version;
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (var category : snapshot.chunks().entrySet()) {
      String layer = switch (category.getKey()) {
        case "country", "orbat", "civic", "info", "decisions" -> category.getKey();
        default -> "map/" + category.getKey();
      };
      int index = 0;
      for (String ref : category.getValue()) {
        String file = directory + "/" + layer + "/" + String.format("%04d", index++) + ".json";
        DurableFiles.writeNew(world.resolve(file), DurableFiles.read(world.resolve(ref)));
      }
      counts.put(category.getKey(), index);
    }
    Pointer next = new Pointer(node.label(), state.contentHash(), directory);
    var manifest = new LinkedHashMap<>(Map.of(
        "node", next.node(), "stateHash", next.stateHash(), "worldId", state.id(), "chunks", counts));
    if (state.simulationTime() != null) manifest.put("simulationTime", state.simulationTime());
    DurableFiles.writeNew(world.resolve(directory + "/manifest.json"), JsonCodec.prettyJson(manifest));
    Path current = world.resolve("view/current.json");
    Pointer previous = null;
    if (Files.isRegularFile(current)) {
      try { previous = JsonCodec.fromJson(DurableFiles.read(current), Pointer.class); }
      catch (RuntimeException ignored) { /* stale view can be replaced */ }
    }
    String temp = "view/.staging/current-" + UUID.randomUUID() + ".json";
    DurableFiles.writeNew(world.resolve(temp), JsonCodec.prettyJson(next));
    DurableFiles.moveAtomic(world.resolve(temp), current, true);
    if (previous != null && previous.directory() != null && previous.directory().matches("view/versions/[0-9a-fA-F-]{36}")) {
      try {
        Path old = world.resolve(previous.directory());
        if (Files.exists(old)) {
          try (var walk = Files.walk(old)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
          }
        }
      } catch (IOException | RuntimeException ignored) {
        // Views are disposable; an old version can be removed on a later refresh.
      }
    }
    return next;
  }
}
