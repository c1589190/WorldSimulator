package io.mosire.worldsim.world;

import io.mosire.worldsim.timeline.TimelineService;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Export the committed file world as a self-contained directory under world/exports. */
public final class WorldExport {
  private WorldExport() {}

  public static String export(FileTimelineStore store) {
    new TimelineService(store).verifyAll();
    WorldDirectory world = store.world();
    String id = UUID.randomUUID().toString();
    String staging = "exports/.staging-" + id;
    String result = "exports/" + id;
    for (String relative : store.committedFiles()) {
      if (relative.equals("world.json")) continue;
      DurableFiles.writeNew(world.resolve(staging + "/" + relative), DurableFiles.read(world.resolve(relative)));
    }
    for (String report : List.of("provenance.json", "migration-report.json", "import-report.json")) {
      if (Files.isRegularFile(world.resolve(report))) {
        DurableFiles.writeNew(world.resolve(staging + "/" + report), DurableFiles.read(world.resolve(report)));
      }
    }
    DurableFiles.writeNew(world.resolve(staging + "/world.json"), DurableFiles.read(world.resolve("world.json")));
    DurableFiles.moveAtomic(world.resolve(staging), world.resolve(result), false);
    return result;
  }
}
