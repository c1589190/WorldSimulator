package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileStoreProcessTest {
  @Test
  void abruptTerminationBeforeAndAfterPublicationRecoversCommittedState(@TempDir Path temp) throws Exception {
    for (String phase : new String[]{"objects-written", "node-published", "manifest-published"}) {
      Path world = temp.resolve(phase);
      Process process = start(world, phase);
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals(42, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      try (var store = FileTimelineStore.open(world)) {
        boolean committed = phase.equals("manifest-published");
        assertEquals(committed ? 2 : 1, new TimelineService(store).verifyAll(), phase);
        assertEquals(committed ? 1 : 0, store.head(new BranchId("main")).orElseThrow().tick(), phase);
        assertEquals(committed, store.receipt("gm:probe", "request").isPresent(), phase);
        if (committed) assertEquals("Crash probe", new TimelineService(store)
            .load(new NodeRef(new BranchId("main"), 1)).map().provinces().get("east").name());
      }
    }
  }

  @Test
  void anotherProcessCannotOpenSameWorldAndLockReleasesOnExit(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    Process holder = start(world, "hold-lock");
    try {
      var reader = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
      assertEquals("LOCKED", reader.readLine());
      assertEquals("WORLD_IN_USE", assertThrows(TimelineException.class, () -> FileTimelineStore.open(world)).code());
    } finally {
      holder.destroyForcibly();
      assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
    }
    try (var reopened = FileTimelineStore.open(world)) {
      assertEquals(1, new TimelineService(reopened).verifyAll());
    }
  }

  private static Process start(Path world, String phase) throws Exception {
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), FileStoreProcessProbe.class.getName(),
        world.toString(), phase).redirectErrorStream(true).start();
  }
}
