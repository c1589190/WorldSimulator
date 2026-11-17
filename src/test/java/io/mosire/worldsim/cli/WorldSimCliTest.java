package io.mosire.worldsim.cli;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.importer.Hoi4FixtureFiles;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.SqliteTimelineStore;
import io.mosire.worldsim.timeline.TimelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSimCliTest {
  @Test
  void demoRunsFullScenarioAndProducesReplayableBranches(@TempDir Path tempDir) {
    Path db = tempDir.resolve("demo.db");
    CliResult demo = capture(() -> WorldSimCli.run(new String[]{
        "demo", "--db", db.toString(), "--reset"}));

    assertEquals(0, demo.exit(), demo.stderr());
    assertTrue(demo.stdout().contains("\"mergedChangesInMain1\" : 2"), demo.stdout());
    assertTrue(demo.stdout().contains("\"historicalAdvanceRejected\" : true"), demo.stdout());
    assertTrue(demo.stdout().contains("\"forkIndependent\" : true"), demo.stdout());
    assertTrue(demo.stdout().contains("\"verifiedNodes\" : 4"), demo.stdout());

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store, 2);
      assertEquals(3, store.nodes(new BranchId("main")).size());
      assertEquals(1, store.nodes(new BranchId("tree2")).size());
      assertEquals(4, service.verifyAll());

      StaticWorldState main2 = service.load(new NodeRef(new BranchId("main"), 2));
      StaticWorldState fork = service.load(new NodeRef(new BranchId("tree2"), 2));
      assertEquals("East revised", main2.map().provinces().get(SampleWorld.EAST).name());
      assertEquals("West revised", main2.map().provinces().get(SampleWorld.WEST).name());
      assertEquals("East fork", fork.map().provinces().get(SampleWorld.EAST).name());
      assertEquals("West", fork.map().provinces().get(SampleWorld.WEST).name());
    }
  }

  @Test
  void importHoi4CommandWritesReplayableCommunityMapGenesis(@TempDir Path tempDir) throws Exception {
    Path source = Hoi4FixtureFiles.write(tempDir.resolve("community-map"));
    Path db = tempDir.resolve("community.db");
    CliResult result = capture(() -> WorldSimCli.run(new String[]{
        "import-hoi4", "--source", source.toString(), "--db", db.toString(), "--reset"}));

    assertEquals(0, result.exit(), result.stderr());
    assertTrue(result.stdout().contains("\"usedProvinces\" : 2"), result.stdout());
    assertTrue(result.stdout().contains("\"verifiedNodes\" : 1"), result.stdout());

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      assertEquals(1, service.verifyAll());
      assertEquals(2, service.load(new NodeRef(new BranchId("main"), 0))
          .map().provinces().size());
    }
  }

  @Test
  void benchmarkGeneratesAndReplaysThreeHundredProvinces(@TempDir Path tempDir) {
    Path db = tempDir.resolve("benchmark.db");
    CliResult result = capture(() -> WorldSimCli.run(new String[]{
        "benchmark", "--db", db.toString(), "--reset", "--columns", "20", "--rows", "15"}));

    assertEquals(0, result.exit(), result.stderr());
    assertTrue(result.stdout().contains("\"provinces\" : 300"), result.stdout());
    assertTrue(result.stdout().contains("\"adjacencies\" : 565"), result.stdout());
    assertTrue(result.stdout().contains("\"validationIssues\" : 0"), result.stdout());
    assertTrue(result.stdout().contains("\"replayedProvince\" : \"Benchmark revised\""), result.stdout());

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineService service = new TimelineService(store);
      assertEquals(2, service.verifyAll());
    }
  }

  @Test
  void resolveCommandReturnsStableMapEntityAtNode(@TempDir Path tempDir) {
    Path db = tempDir.resolve("demo.db");
    assertEquals(0, capture(() -> WorldSimCli.run(new String[]{
        "demo", "--db", db.toString(), "--reset"})).exit());

    CliResult result = capture(() -> WorldSimCli.run(new String[]{
        "resolve", "--db", db.toString(), "--address", "main-2/map/province/east"}));
    assertEquals(0, result.exit(), result.stderr());
    assertTrue(result.stdout().contains("\"stableId\" : \"province:east\""), result.stdout());
    assertTrue(result.stdout().contains("\"displayName\" : \"East revised\""), result.stdout());
    assertTrue(result.stdout().contains("\"kind\" : \"province\""), result.stdout());
  }

  @Test
  void replayAndVerifyCommandsReturnZero(@TempDir Path tempDir) {
    Path db = tempDir.resolve("demo.db");
    assertEquals(0, capture(() -> WorldSimCli.run(new String[]{
        "demo", "--db", db.toString(), "--reset"})).exit());

    CliResult replay = capture(() -> WorldSimCli.run(new String[]{
        "replay", "--db", db.toString(), "--node", "main-2"}));
    assertEquals(0, replay.exit(), replay.stderr());
    assertTrue(replay.stdout().contains("\"validated\" : true"), replay.stdout());

    CliResult verify = capture(() -> WorldSimCli.run(new String[]{
        "verify", "--db", db.toString()}));
    assertEquals(0, verify.exit(), verify.stderr());
    assertTrue(verify.stdout().contains("\"verifiedNodes\" : 4"), verify.stdout());
  }

  @Test
  void existingDemoDatabaseRequiresReset(@TempDir Path tempDir) {
    Path db = tempDir.resolve("demo.db");
    assertEquals(0, capture(() -> WorldSimCli.run(new String[]{
        "demo", "--db", db.toString(), "--reset"})).exit());

    CliResult second = capture(() -> WorldSimCli.run(new String[]{
        "demo", "--db", db.toString()}));
    assertEquals(1, second.exit());
    assertTrue(second.stderr().contains("--reset"), second.stderr());

    CliResult reset = capture(() -> WorldSimCli.run(new String[]{
        "demo", "--db", db.toString(), "--reset"}));
    assertEquals(0, reset.exit(), reset.stderr());
  }

  private static CliResult capture(IntSupplier action) {
    PrintStream oldOut = System.out;
    PrintStream oldErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit;
    try {
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      exit = action.getAsInt();
    } finally {
      System.setOut(oldOut);
      System.setErr(oldErr);
    }
    return new CliResult(exit, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private record CliResult(int exit, String stdout, String stderr) {}
}
