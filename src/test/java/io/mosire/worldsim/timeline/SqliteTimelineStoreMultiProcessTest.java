package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTimelineStoreMultiProcessTest {
  @Test
  @Timeout(45)
  void twoRealJvmsCannotOverwriteSameNode(@TempDir Path tempDir) throws Exception {
    Path db = tempDir.resolve("multi-process.db");
    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      store.saveNode(new TimelineNode(
          ref("main-0"),
          null,
          "{\"changes\":[]}",
          "sha256:genesis",
          1_700_000_000_000L,
          "test"), "{\"root\":true}");
    }

    Process first = startProbe(db, "main-1", "main-0", "sha256:first");
    Process second = startProbe(db, "main-1", "main-0", "sha256:second");

    ProcessResult firstResult = await(first);
    ProcessResult secondResult = await(second);

    int successes = (firstResult.exitCode() == 0 ? 1 : 0)
        + (secondResult.exitCode() == 0 ? 1 : 0);
    assertEquals(1, successes,
        "恰好一个 JVM 应成功；first=" + firstResult + " second=" + secondResult);
    assertTrue(firstResult.output().contains("OK") ^ secondResult.output().contains("OK"));

    try (SqliteTimelineStore store = new SqliteTimelineStore(db)) {
      TimelineNode saved = store.find(ref("main-1")).orElseThrow();
      assertEquals(ref("main-1"), store.head(new BranchId("main")).orElseThrow());
      assertEquals(2, store.nodes(new BranchId("main")).size());
      assertTrue(saved.stateHash().equals("sha256:first")
          || saved.stateHash().equals("sha256:second"));
      assertNotEquals(null, saved.savedBy());
    }
  }

  private static Process startProbe(Path db, String ref, String parent, String hash)
      throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new ProcessBuilder(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        StoreProbe.class.getName(),
        db.toString(),
        ref,
        parent,
        hash)
        .redirectErrorStream(true)
        .start();
  }

  private static ProcessResult await(Process process) throws Exception {
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("StoreProbe 超时未结束");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    return new ProcessResult(process.exitValue(), output);
  }

  private static NodeRef ref(String text) {
    int dash = text.lastIndexOf('-');
    return new NodeRef(new BranchId(text.substring(0, dash)),
        Long.parseLong(text.substring(dash + 1)));
  }

  private record ProcessResult(int exitCode, String output) {}
}
