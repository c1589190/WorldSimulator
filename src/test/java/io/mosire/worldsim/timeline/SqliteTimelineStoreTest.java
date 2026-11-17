package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTimelineStoreTest {
  private SqliteTimelineStore store;

  @BeforeEach
  void setUp() {
    store = SqliteTimelineStore.inMemory();
  }

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  void savesReadsAndTraversesNodes() {
    TimelineNode genesis = node("main-0", null, "sha256:genesis");
    store.saveNode(genesis, "{\"id\":\"world\"}");

    assertFalse(store.isEmpty());
    assertEquals(genesis, store.find(ref("main-0")).orElseThrow());
    assertEquals(genesis, store.genesis().orElseThrow());
    assertTrue(store.exists(ref("main-0")));
    assertEquals(List.of(ref("main-0")), store.pathToRoot(ref("main-0")).stream()
        .map(TimelineNode::ref).toList());
    assertEquals("{\"id\":\"world\"}", store.checkpoint(ref("main-0")).orElseThrow());
    assertEquals(Set.of(new BranchId("main")), store.branches());
    assertEquals(ref("main-0"), store.head(new BranchId("main")).orElseThrow());

    TimelineNode main1 = node("main-1", "main-0", "sha256:main1");
    store.saveNode(main1, null);
    assertTrue(store.checkpoint(ref("main-1")).isEmpty());
    assertEquals(List.of(ref("main-0"), ref("main-1")),
        store.pathToRoot(ref("main-1")).stream().map(TimelineNode::ref).toList());
    assertEquals(List.of(genesis, main1), store.nodes(new BranchId("main")));
    assertEquals(ref("main-1"), store.head(new BranchId("main")).orElseThrow());
  }

  @Test
  void allowsForkBranchFromAnySavedNode() {
    store.saveNode(node("main-0", null, "sha256:genesis"), "{\"root\":true}");
    store.saveNode(node("main-1", "main-0", "sha256:main1"), null);
    store.saveNode(node("main-2", "main-1", "sha256:main2"), null);

    TimelineNode fork = node("tree2-5", "main-1", "sha256:fork");
    store.saveNode(fork, "{\"fork\":true}");

    assertEquals(fork, store.find(ref("tree2-5")).orElseThrow());
    assertEquals(ref("tree2-5"), store.head(new BranchId("tree2")).orElseThrow());
    assertEquals(List.of(ref("main-0"), ref("main-1"), ref("tree2-5")),
        store.pathToRoot(ref("tree2-5")).stream().map(TimelineNode::ref).toList());
    assertEquals("{\"fork\":true}", store.checkpoint(ref("tree2-5")).orElseThrow());
  }

  @Test
  void refusesDuplicateAndOverwrite() {
    store.saveNode(node("main-0", null, "sha256:genesis"), "{}");
    assertCode("TIMELINE_NODE_EXISTS", () ->
        store.saveNode(node("main-0", null, "sha256:other"), "{}"));
  }

  @Test
  void refusesMissingParentAndNonHeadParent() {
    assertCode("TIMELINE_PARENT_NOT_SAVED", () ->
        store.saveNode(node("main-1", "main-0", "sha256:x"), null));

    store.saveNode(node("main-0", null, "sha256:0"), "{}");
    store.saveNode(node("main-1", "main-0", "sha256:1"), null);
    assertCode("TIMELINE_PARENT_NOT_HEAD", () ->
        store.saveNode(node("main-2", "main-0", "sha256:2"), null));
  }

  @Test
  void enforcesTickAndGenesisRules() {
    store.saveNode(node("main-0", null, "sha256:0"), "{}");
    store.saveNode(node("main-1", "main-0", "sha256:1"), null);
    assertCode("TIMELINE_TICK_NOT_ADVANCING", () ->
        store.saveNode(node("tree9-1", "main-1", "sha256:x"), null));
    assertCode("TIMELINE_GENESIS_TICK", () ->
        store.saveNode(node("tree8-1", null, "sha256:other"), "{}"));
    assertCode("TIMELINE_GENESIS_ALREADY_EXISTS", () ->
        store.saveNode(node("tree7-0", null, "sha256:other"), "{}"));
  }

  @Test
  void checkpointCanBeCreatedAndRefreshedIndependently() {
    store.saveNode(node("main-0", null, "sha256:0"), null);
    assertTrue(store.checkpoint(ref("main-0")).isEmpty());

    store.saveCheckpoint(ref("main-0"), "{\"v\":1}");
    assertEquals("{\"v\":1}", store.checkpoint(ref("main-0")).orElseThrow());
    store.saveCheckpoint(ref("main-0"), "{\"v\":2}");
    assertEquals("{\"v\":2}", store.checkpoint(ref("main-0")).orElseThrow());

    assertCode("TIMELINE_NODE_NOT_FOUND", () ->
        store.saveCheckpoint(ref("main-99"), "{}"));
  }

  @Test
  void pathToRootReturnsEmptyForMissingNodeAndRejectsClosedStore() {
    assertTrue(store.pathToRoot(ref("missing-0")).isEmpty());
    assertFalse(store.exists(ref("missing-0")));

    store.close();
    assertCode("TIMELINE_STORE_CLOSED", () -> store.find(ref("missing-0")));
  }

  @Test
  void schemaVersionIsPinnedAndSetOnCreate() {
    assertEquals(SqliteTimelineStore.SCHEMA_VERSION, store.schemaVersion());
    assertEquals(1, store.schemaVersion());
  }

  @Test
  void versionZeroDatabaseIsUpgraded(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("v0.db");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
         Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = 0");
    }

    try (SqliteTimelineStore upgraded = new SqliteTimelineStore(file)) {
      assertEquals(SqliteTimelineStore.SCHEMA_VERSION, upgraded.schemaVersion());
      assertTrue(upgraded.isEmpty());
    }
  }

  @Test
  void newerSchemaIsRejectedWithoutLeavingConnectionOpen(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("future.db");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
         Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = " + (SqliteTimelineStore.SCHEMA_VERSION + 1));
    }

    assertCode("TIMELINE_SCHEMA_TOO_NEW", () -> new SqliteTimelineStore(file));

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
         Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = " + SqliteTimelineStore.SCHEMA_VERSION);
    }
    try (SqliteTimelineStore recovered = new SqliteTimelineStore(file)) {
      assertEquals(SqliteTimelineStore.SCHEMA_VERSION, recovered.schemaVersion());
    }
  }

  @Test
  void fileDatabaseSurvivesReopen(@TempDir Path tempDir) {
    Path file = tempDir.resolve("nested/timeline.db");
    try (SqliteTimelineStore first = new SqliteTimelineStore(file)) {
      first.saveNode(node("main-0", null, "sha256:0"), "{\"root\":true}");
      first.saveNode(node("main-1", "main-0", "sha256:1"), null);
    }
    assertTrue(java.nio.file.Files.exists(file));

    try (SqliteTimelineStore second = new SqliteTimelineStore(file)) {
      assertEquals(2, second.nodes(new BranchId("main")).size());
      assertEquals(ref("main-1"), second.head(new BranchId("main")).orElseThrow());
      assertEquals("{\"root\":true}", second.checkpoint(ref("main-0")).orElseThrow());
    }
  }

  private static TimelineNode node(String refText, String parentText, String stateHash) {
    return new TimelineNode(
        ref(refText),
        parentText == null ? null : ref(parentText),
        "{\"changes\":[]}",
        stateHash,
        1_700_000_000_000L,
        "test");
  }

  private static NodeRef ref(String text) {
    if (text.contains("/")) {
      throw new IllegalArgumentException(text);
    }
    int dash = text.lastIndexOf('-');
    return new NodeRef(new BranchId(text.substring(0, dash)),
        Long.parseLong(text.substring(dash + 1)));
  }

  private static void assertCode(String code, Runnable operation) {
    TimelineException error = assertThrows(TimelineException.class, operation::run);
    assertEquals(code, error.code(), error.getMessage());
  }
}
