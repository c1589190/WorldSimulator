package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineServiceTest {
  private SqliteTimelineStore store;
  private TimelineService service;
  private StaticWorldState initial;
  private TimelineNode genesis;

  @BeforeEach
  void setUp() {
    store = SqliteTimelineStore.inMemory();
    service = new TimelineService(store, 2);
    initial = SampleWorld.create();
    genesis = service.createGenesis(initial, "system:test");
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void genesisSavesBaselineCheckpointAndIsUnique() {
    assertTrue(store.checkpoint(genesis.ref()).isPresent());
    assertEquals(initial.contentHash(), genesis.stateHash());
    assertEquals(initial.contentHash(), service.load(genesis.ref()).contentHash());
    assertEquals(WorldChangeSet.empty(), WorldChangeSet.fromJson(genesis.changeSetJson()));
    assertEquals(genesis.ref(), store.head(new BranchId("main")).orElseThrow());

    assertCode("TIMELINE_GENESIS_ALREADY_EXISTS",
        () -> service.createGenesis(initial, "system:test"));
  }

  @Test
  void sameWorkingCopyWithTwoWritesProducesOneOrderedChangeSet() {
    TimelineWorkingCopy workingCopy = service.openNext(genesis.ref());
    service.apply(workingCopy, rename(initial, SampleWorld.EAST, "East one"));
    service.apply(workingCopy, rename(initial, SampleWorld.EAST, "East two"));

    assertEquals(2, workingCopy.pendingChangeCount());
    assertEquals("East two",
        workingCopy.currentState().map().provinces().get(SampleWorld.EAST).name());
    assertFalse(store.exists(new NodeRef(new BranchId("main"), 1)));
    assertEquals(genesis.ref(), store.head(new BranchId("main")).orElseThrow());

    TimelineNode main1 = service.save(workingCopy, 1, "gm:test");

    assertEquals(genesis.ref(), main1.parent());
    assertEquals(2, store.nodes(new BranchId("main")).size());
    WorldChangeSet saved = WorldChangeSet.fromJson(main1.changeSetJson());
    assertEquals(2, saved.changes().size());
    assertEquals("East two", service.load(main1.ref()).map().provinces().get(SampleWorld.EAST).name());
    assertFalse(workingCopy.isOpen());
    assertEquals(main1.ref(), workingCopy.savedRef().orElseThrow());
  }

  @Test
  void discardDropsMemoryChangesAndDoesNotMoveHead() {
    TimelineWorkingCopy workingCopy = service.openNext(genesis.ref());
    service.apply(workingCopy, rename(initial, SampleWorld.EAST, "Discarded"));
    String dirtyHash = workingCopy.currentState().contentHash();
    assertNotEquals(genesis.stateHash(), dirtyHash);

    service.discard(workingCopy);

    assertFalse(workingCopy.isOpen());
    assertTrue(workingCopy.pendingChanges().isEmpty());
    assertEquals(workingCopy.baseState().contentHash(), workingCopy.currentState().contentHash());
    assertEquals(genesis.ref(), store.head(new BranchId("main")).orElseThrow());
    assertFalse(store.exists(new NodeRef(new BranchId("main"), 1)));
    assertEquals(genesis.stateHash(), service.load(genesis.ref()).contentHash());
  }

  @Test
  void onlyHeadCanAdvanceAndHistoricalNodesMustFork() {
    TimelineNode main1 = saveRename(genesis.ref(), 1, SampleWorld.EAST, "East one");
    TimelineNode main2 = saveRename(main1.ref(), 2, SampleWorld.WEST, "West two");

    assertCode("TIMELINE_FORK_REQUIRED", () -> service.openNext(genesis.ref()));
    assertCode("TIMELINE_FORK_REQUIRED", () -> service.openNext(main1.ref()));
    assertEquals(main2.ref(), store.head(new BranchId("main")).orElseThrow());

    TimelineWorkingCopy fork = service.openFork(main1.ref(), new BranchId("tree2"));
    service.apply(fork, rename(service.load(main1.ref()), SampleWorld.EAST, "East fork"));
    TimelineNode forkNode = service.save(fork, 2, "gm:test");

    assertEquals(main1.ref(), forkNode.parent());
    assertEquals(new BranchId("tree2"), forkNode.ref().branch());
    assertEquals(forkNode.ref(), store.head(new BranchId("tree2")).orElseThrow());
    assertCode("TIMELINE_NODE_NOT_FOUND",
        () -> service.openNext(new NodeRef(new BranchId("missing"), 0)));
    assertCode("TIMELINE_NODE_NOT_FOUND",
        () -> service.openFork(new NodeRef(new BranchId("missing"), 0), new BranchId("tree9")));
  }

  @Test
  void forkBranchIsIndependentFromLaterParentBranchChanges() {
    TimelineNode main1 = saveRename(genesis.ref(), 1, SampleWorld.EAST, "East revised");

    TimelineWorkingCopy fork = service.openFork(main1.ref(), new BranchId("tree2"));
    service.apply(fork, rename(service.load(main1.ref()), SampleWorld.EAST, "East fork"));
    TimelineNode forkNode = service.save(fork, 2, "gm:test");

    TimelineNode main2 = saveRename(main1.ref(), 2, SampleWorld.WEST, "West revised");

    StaticWorldState tree2 = service.load(forkNode.ref());
    assertEquals("East fork", tree2.map().provinces().get(SampleWorld.EAST).name());
    assertEquals("West", tree2.map().provinces().get(SampleWorld.WEST).name());

    StaticWorldState main = service.load(main2.ref());
    assertEquals("East revised", main.map().provinces().get(SampleWorld.EAST).name());
    assertEquals("West revised", main.map().provinces().get(SampleWorld.WEST).name());
  }

  @Test
  void forkRulesRejectSameOrExistingBranch() {
    assertCode("TIMELINE_FORK_SAME_BRANCH",
        () -> service.openFork(genesis.ref(), new BranchId("main")));

    TimelineWorkingCopy fork = service.openFork(genesis.ref(), new BranchId("tree2"));
    service.apply(fork, rename(initial, SampleWorld.EAST, "Fork"));
    service.save(fork, 1, "gm:test");

    assertCode("TIMELINE_BRANCH_EXISTS",
        () -> service.openFork(genesis.ref(), new BranchId("tree2")));
  }

  @Test
  void invalidStateIsRejectedWithoutWritingOrClosingWorkingCopy() {
    TimelineWorkingCopy workingCopy = service.openNext(genesis.ref());
    service.apply(workingCopy, new WorldChange.RemoveState(SampleWorld.STATE));

    assertCode("TIMELINE_STATE_INVALID", () -> service.save(workingCopy, 1, "gm:test"));

    assertTrue(workingCopy.isOpen());
    assertFalse(store.exists(new NodeRef(new BranchId("main"), 1)));
    assertEquals(genesis.ref(), store.head(new BranchId("main")).orElseThrow());
    service.discard(workingCopy);
  }

  @Test
  void periodicCheckpointIsUsedForReplayAndCanBeRefreshed() {
    TimelineNode main1 = saveRename(genesis.ref(), 1, SampleWorld.EAST, "East one");
    TimelineNode main2 = saveRename(main1.ref(), 2, SampleWorld.WEST, "West two");

    assertTrue(store.checkpoint(genesis.ref()).isPresent());
    assertTrue(store.checkpoint(main1.ref()).isPresent());
    assertTrue(store.checkpoint(main2.ref()).isEmpty());

    assertEquals(main2.stateHash(), service.load(main2.ref()).contentHash());

    service.checkpoint(main2.ref());
    assertTrue(store.checkpoint(main2.ref()).isPresent());
    assertEquals(main2.stateHash(), service.replay(main2.ref()).contentHash());
  }

  @Test
  void firstNodeOfForkIsAlwaysCheckpointed() {
    TimelineService sparse = new TimelineService(store, 100);
    TimelineWorkingCopy advance = sparse.openNext(genesis.ref());
    sparse.apply(advance, rename(initial, SampleWorld.EAST, "East one"));
    TimelineNode saved = sparse.save(advance, 1, "gm:test");
    assertTrue(store.checkpoint(saved.ref()).isEmpty());

    TimelineWorkingCopy fork = sparse.openFork(genesis.ref(), new BranchId("tree2"));
    sparse.apply(fork, rename(initial, SampleWorld.WEST, "West fork"));
    TimelineNode forkNode = sparse.save(fork, 1, "gm:test");
    assertTrue(store.checkpoint(forkNode.ref()).isPresent());
  }

  @Test
  void nodesWithoutCheckpointAncestryCannotBeLoaded() {
    try (SqliteTimelineStore otherStore = SqliteTimelineStore.inMemory()) {
      TimelineService otherService = new TimelineService(otherStore);
      StaticWorldState state = SampleWorld.create();
      TimelineNode root = new TimelineNode(
          new NodeRef(new BranchId("main"), 0),
          null,
          WorldChangeSet.emptyJson(),
          state.contentHash(),
          1_700_000_000_000L,
          "test");
      otherStore.saveNode(root, null);

      assertCode("TIMELINE_CHECKPOINT_MISSING", () -> otherService.load(root.ref()));
    }
  }

  @Test
  void replayDetectsContentHashMismatch() {
    store.saveNode(new TimelineNode(
        new NodeRef(new BranchId("main"), 1),
        genesis.ref(),
        WorldChangeSet.emptyJson(),
        "sha256:definitely-wrong",
        1_700_000_000_000L,
        "corruptor"), null);

    assertCode("TIMELINE_REPLAY_HASH_MISMATCH", () -> service.load(
        new NodeRef(new BranchId("main"), 1)));
  }

  @Test
  void staleWorkingCopyCannotOverwriteAdvancedHead() {
    TimelineWorkingCopy first = service.openNext(genesis.ref());
    TimelineWorkingCopy second = service.openNext(genesis.ref());

    service.apply(first, rename(initial, SampleWorld.EAST, "First writer"));
    service.save(first, 1, "gm:first");

    service.apply(second, rename(initial, SampleWorld.EAST, "Second writer"));
    assertCode("TIMELINE_FORK_REQUIRED", () -> service.save(second, 2, "gm:second"));

    assertEquals(2, store.nodes(new BranchId("main")).size());
    assertEquals("First writer",
        service.load(new NodeRef(new BranchId("main"), 1)).map()
            .provinces().get(SampleWorld.EAST).name());
  }

  @Test
  void expectedContentHashGuardsOpenForkAndSave() {
    assertCode("TIMELINE_EXPECTED_HASH_MISMATCH",
        () -> service.openNext(genesis.ref(), "sha256:wrong"));
    assertCode("TIMELINE_EXPECTED_HASH_INVALID",
        () -> service.openNext(genesis.ref(), " "));
    assertCode("TIMELINE_EXPECTED_HASH_MISMATCH",
        () -> service.openFork(genesis.ref(), new BranchId("tree2"), "sha256:wrong"));

    TimelineWorkingCopy workingCopy = service.openNext(genesis.ref(), genesis.stateHash());
    service.apply(workingCopy, rename(initial, SampleWorld.EAST, "Expected hash writer"));
    assertCode("TIMELINE_EXPECTED_HASH_MISMATCH",
        () -> service.save(workingCopy, 1, "gm:test", "sha256:wrong"));
    assertFalse(store.exists(new NodeRef(new BranchId("main"), 1)));
    assertTrue(workingCopy.isOpen());

    TimelineNode saved = service.save(workingCopy, 1, "gm:test", genesis.stateHash());
    assertEquals("Expected hash writer",
        service.load(saved.ref()).map().provinces().get(SampleWorld.EAST).name());
  }

  @Test
  void tickMustAdvanceAndWorkingCopyCannotBeSavedTwice() {
    TimelineWorkingCopy workingCopy = service.openNext(genesis.ref());
    assertCode("TIMELINE_TICK_NOT_ADVANCING", () -> service.save(workingCopy, 0, "gm:test"));

    TimelineNode main1 = service.save(workingCopy, 1, "gm:test");
    assertEquals(1, main1.ref().tick());
    assertCode("TIMELINE_WORKING_COPY_CLOSED", () -> service.save(workingCopy, 2, "gm:test"));
  }

  @Test
  void verifyAllWalksEveryBranchNode() {
    TimelineNode main1 = saveRename(genesis.ref(), 1, SampleWorld.EAST, "East one");
    saveRename(main1.ref(), 2, SampleWorld.WEST, "West two");

    TimelineWorkingCopy fork = service.openFork(genesis.ref(), new BranchId("tree2"));
    service.apply(fork, rename(initial, SampleWorld.EAST, "Fork"));
    service.save(fork, 1, "gm:test");

    assertEquals(4, service.verifyAll());
  }

  private TimelineNode saveRename(NodeRef base, long tick, String provinceId, String newName) {
    TimelineWorkingCopy workingCopy = service.openNext(base);
    service.apply(workingCopy, rename(service.load(base), provinceId, newName));
    return service.save(workingCopy, tick, "gm:test");
  }

  private static WorldChange rename(StaticWorldState state, String provinceId, String newName) {
    return new WorldChange.PutProvince(
        SampleWorld.renameProvince(state.map(), provinceId, newName));
  }

  private static void assertCode(String code, Runnable operation) {
    TimelineException error = assertThrows(TimelineException.class, operation::run);
    assertEquals(code, error.code(), error.getMessage());
  }
}
