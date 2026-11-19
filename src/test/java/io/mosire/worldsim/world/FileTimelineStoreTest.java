package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.CommitReceipt;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FileTimelineStoreTest {
  @Test
  void savesReopensForksAndSharesUnchangedGeometry(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    var state = SampleWorld.create();
    NodeRef main1;
    NodeRef fork1;
    try (var store = FileTimelineStore.create(world, state.id())) {
      var timeline = new TimelineService(store, 2);
      var root = timeline.createGenesis(state, "gm:test");
      long geometryObjects = geometryObjects(world);
      var work = timeline.openNext(root.ref(), root.stateHash());
      timeline.apply(work, new WorldChange.PutProvince(SampleWorld.renameProvince(state.map(), "east", "Renamed")));
      var target = new NodeRef(new BranchId("main"), 1);
      var receipt = new CommitReceipt("gm:test", "request-1", "sha256:test", target);
      main1 = timeline.save(work, 1, "gm:test", root.stateHash(), receipt).ref();
      assertEquals(geometryObjects, geometryObjects(world), "unchanged geometry should be shared");
      var fork = timeline.openFork(root.ref(), new BranchId("other"));
      timeline.apply(fork, new WorldChange.PutProvince(SampleWorld.renameProvince(state.map(), "west", "Fork")));
      fork1 = timeline.save(fork, 2, "gm:test").ref();
      assertEquals(3, timeline.verifyAll());
      assertEquals(receipt, store.receipt("gm:test", "request-1").orElseThrow());
      assertEquals("WORLD_IN_USE", assertThrows(TimelineException.class, () -> FileTimelineStore.open(world)).code());
    }
    try (var store = FileTimelineStore.open(world)) {
      var timeline = new TimelineService(store);
      assertEquals(3, timeline.verifyAll());
      assertEquals("Renamed", timeline.load(main1).map().provinces().get("east").name());
      assertEquals("Fork", timeline.load(fork1).map().provinces().get("west").name());
      assertEquals("request-1", store.receipt("gm:test", "request-1").orElseThrow().requestId());
      assertFalse(Files.exists(world.resolve("cache/index.sqlite")));
    }
  }

  @Test
  void uncommittedPublishedFilesAreIgnoredAndRetryCanUseSameNode(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    var armed = new AtomicBoolean();
    var state = SampleWorld.create();
    try (var store = FileTimelineStore.create(world, state.id(), phase -> {
      if (armed.get() && phase.equals("node-published")) throw new IllegalStateException("simulated crash");
    })) {
      var timeline = new TimelineService(store);
      var root = timeline.createGenesis(state, "gm:test");
      var work = timeline.openNext(root.ref());
      timeline.apply(work, new WorldChange.PutProvince(SampleWorld.renameProvince(state.map(), "east", "Draft")));
      armed.set(true);
      assertThrows(IllegalStateException.class, () -> timeline.save(work, 1, "gm:test"));
      assertEquals(root.ref(), store.head(new BranchId("main")).orElseThrow());
    }
    try (var store = FileTimelineStore.open(world)) {
      var timeline = new TimelineService(store);
      assertEquals(1, timeline.verifyAll());
      var work = timeline.openNext(new NodeRef(new BranchId("main"), 0));
      timeline.apply(work, new WorldChange.PutProvince(SampleWorld.renameProvince(state.map(), "east", "Retried")));
      var saved = timeline.save(work, 1, "gm:test");
      assertEquals("Retried", timeline.load(saved.ref()).map().provinces().get("east").name());
      Path exported = world.resolve(WorldExport.export(store));
      try (var files = Files.walk(exported)) {
        var changes = files.filter(p -> p.getFileName().toString().equals("changes.json")).toList();
        assertEquals(2, changes.size());
        for (Path file : changes) assertFalse(Files.readString(file).contains("Draft"));
      }
    }
  }

  @Test
  void detectsChangedNodeFileAndUnsupportedWorldFormat(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    var manifest = JsonCodec.mapper().readTree(Files.readString(world.resolve("world.json")));
    Path changes = world.resolve(manifest.path("heads").path("main").asText()).resolve("changes.json");
    Files.writeString(changes, "{\"changes\":[]}");
    assertEquals("WORLD_CHANGE_HASH_MISMATCH", assertThrows(TimelineException.class, () -> FileTimelineStore.open(world)).code());
    Files.writeString(world.resolve("world.json"), "{\"formatVersion\":999}");
    assertEquals("WORLD_FORMAT_UNSUPPORTED", assertThrows(TimelineException.class, () -> FileTimelineStore.open(world)).code());
  }

  @Test
  void fullVerificationRereadsNodeFilesEvenWhileWorldIsOpen(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var timeline = new TimelineService(store);
      timeline.createGenesis(SampleWorld.create(), "gm:test");
      var manifest = JsonCodec.mapper().readTree(Files.readString(world.resolve("world.json")));
      Path changes = world.resolve(manifest.path("heads").path("main").asText()).resolve("changes.json");
      Files.writeString(changes, "{\"changes\":[]}");
      assertEquals("WORLD_CHANGE_HASH_MISMATCH", assertThrows(TimelineException.class, timeline::verifyAll).code());
      assertEquals("WORLD_REOPEN_REQUIRED", assertThrows(TimelineException.class, store::branches).code());
    }
  }

  @Test
  void cachedReplaySharesMapsAcrossForkCheckpointsAndFullVerificationReadsDisk(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var timeline = new TimelineService(store);
      var root = timeline.createGenesis(SampleWorld.create(), "gm:test");
      var fork = timeline.openFork(root.ref(), new BranchId("other"));
      timeline.apply(fork, new WorldChange.SetSimulationTime("1936-01-01T01:00:00"));
      var saved = timeline.save(fork, 1, "gm:test");
      var work = timeline.openNext(saved.ref());
      timeline.apply(work, new WorldChange.SetSimulationTime("1936-01-01T02:00:00"));
      var next = timeline.save(work, 2, "gm:test");
      var latest = timeline.loadCached(next.ref());
      assertEquals(next.stateHash(), latest.contentHash());
      assertSame(latest.map(), timeline.loadCached(saved.ref()).map());
      assertSame(latest.map(), timeline.loadCached(root.ref()).map());
      assertEquals(3, timeline.verifyAll());
      var geometry = store.committedFiles().stream().filter(path -> path.contains("/objects/geometry/")).findFirst().orElseThrow();
      Files.writeString(world.resolve(geometry), "\n", StandardOpenOption.APPEND);
      assertEquals("WORLD_OBJECT_HASH_MISMATCH", assertThrows(TimelineException.class, timeline::verifyAll).code());
    }
  }

  private static long geometryObjects(Path world) throws IOException {
    try (var paths = Files.walk(world.resolve("data"))) {
      return paths.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("geometry"))
          .filter(Files::isRegularFile).count();
    }
  }
}
