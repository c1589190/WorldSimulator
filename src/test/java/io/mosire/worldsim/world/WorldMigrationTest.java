package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.SqliteTimelineStore;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldMigrationTest {
  @Test
  void convertsBranchesAndPreservesOriginalDatabaseAndHashes(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world with spaces");
    Path imports = Files.createDirectories(world.resolve("imports"));
    Path oldDb = imports.resolve("legacy world.db");
    String rootHash;
    String childHash;
    try (var old = new SqliteTimelineStore(oldDb)) {
      var service = new TimelineService(old, 2);
      var initial = SampleWorld.create();
      var root = service.createGenesis(initial, "gm:test");
      rootHash = root.stateHash();
      var work = service.openNext(root.ref());
      service.apply(work, new WorldChange.PutProvince(SampleWorld.renameProvince(initial.map(), "east", "After")));
      childHash = service.save(work, 1, "gm:test").stateHash();
      var fork = service.openFork(root.ref(), new BranchId("fork"));
      service.apply(fork, new WorldChange.PutProvince(SampleWorld.renameProvince(initial.map(), "west", "Fork")));
      service.save(fork, 2, "gm:test");
    }
    byte[] original = Files.readAllBytes(oldDb);
    var report = WorldMigration.migrateSqlite(world, oldDb);
    assertArrayEquals(original, Files.readAllBytes(oldDb));
    assertEquals(3, report.nodes());
    assertEquals(rootHash, report.genesisHash());
    try (var converted = FileTimelineStore.open(world)) {
      var timeline = new TimelineService(converted);
      assertEquals(3, timeline.verifyAll());
      assertEquals(childHash, converted.find(new NodeRef(new BranchId("main"), 1)).orElseThrow().stateHash());
      assertEquals("Fork", timeline.load(new NodeRef(new BranchId("fork"), 2)).map().provinces().get("west").name());
      var head = new NodeRef(new BranchId("main"), 1);
      var state = timeline.load(head);
      var view = WorldView.refresh(converted, head, state);
      assertEquals("main-1", view.node());
      var pointer = JsonCodec.fromJson(Files.readString(world.resolve("view/current.json")), WorldView.Pointer.class);
      assertEquals(childHash, pointer.stateHash());
      assertTrue(Files.readString(world.resolve(pointer.directory()).resolve("map/province/0000.json")).contains("After"));
    }
    assertEquals("WORLD_ALREADY_INITIALIZED", assertThrows(TimelineException.class,
        () -> WorldMigration.migrateSqlite(world, oldDb)).code());
  }

  @Test
  void rejectsSourceOutsideWorldImports(@TempDir Path temp) throws Exception {
    Path outside = temp.resolve("outside.db");
    try (var old = new SqliteTimelineStore(outside)) {
      new TimelineService(old).createGenesis(SampleWorld.create(), "gm:test");
    }
    Path world = temp.resolve("world");
    Files.createDirectories(world.resolve("imports"));
    assertEquals("WORLD_IMPORT_OUTSIDE", assertThrows(TimelineException.class,
        () -> WorldMigration.migrateSqlite(world, outside)).code());
    assertFalse(Files.exists(world.resolve("world.json")));
  }

  @Test
  void rejectsImportDirectoriesContainingLinksOutsideWorld(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    Path source = Files.createDirectories(world.resolve("imports/map"));
    Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
    try {
      Files.createSymbolicLink(source.resolve("link.txt"), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
      org.junit.jupiter.api.Assumptions.abort("filesystem does not support symlinks");
    }
    try (WorldDirectory directory = WorldDirectory.open(world)) {
      assertEquals("WORLD_UNSAFE_PATH", assertThrows(TimelineException.class,
          () -> directory.requireImportDirectory(source)).code());
    }
  }
}
