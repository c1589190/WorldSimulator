package io.mosire.worldsim.cli;

import io.mosire.worldsim.importer.Hoi4FixtureFiles;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldDirectoryCliProcessTest {
  @Test
  void initHoi4RecordsSourceInferenceAndLoadsWithoutDatabase(@TempDir Path temp) throws Exception {
    Hoi4FixtureFiles.writeWithWaterProvince(temp.resolve("world/imports/water-map"));
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), WorldSimCli.class.getName(), "init-hoi4",
        "--source", "world/imports/water-map").directory(temp.toFile()).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), stderr);
    Path world = temp.resolve("world");
    var provenance = JsonCodec.mapper().readTree(Files.readString(world.resolve("provenance.json")));
    assertEquals("2", provenance.path("fieldEvidence").get(0).path("ids").get(0).asText());
    assertEquals("inferred", provenance.path("fieldEvidence").get(0).path("status").asText());
    assertTrue(Files.isRegularFile(world.resolve("import-report.json")));
    try (var store = FileTimelineStore.open(world)) {
      assertEquals(provenance.path("stateHash").asText(), store.genesis().orElseThrow().stateHash());
      assertFalse(Files.exists(world.resolve("cache/index.sqlite")));
    }
  }
}
