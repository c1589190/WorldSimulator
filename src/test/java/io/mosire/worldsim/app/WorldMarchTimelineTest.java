package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldMarchTimelineTest {
  private static final String GM = "gm:test";
  private static final Map<String, String> SOURCE = Map.of("source", "fixture:march", "status", "estimated");

  @Test
  void marchesThroughSeveralLegsAndReplaysAcrossASeparateCancellationBranch(@TempDir Path world) throws Exception {
    JsonNode expected;
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), GM);
      var reference = new WorldGeography.Reference("worldsim-geo-v1", "fixture", "main-0", root.stateHash(),
          200, 100, "fixture:calibration", List.of(new Vec2(-1, -1), new Vec2(1, -1), new Vec2(1, 1), new Vec2(-1, 1)),
          List.of(50.0, 180_000 / Math.PI, 0.0, 50.0, 0.0, -180_000 / Math.PI), 1,
          new WorldGeography.Check(3, 1, 1, 1), List.of(), List.of(), null, null);
      Files.writeString(world.resolve("geo-reference.json"), JsonCodec.canonicalJson(reference));
      var app = new WorldApplicationService(store);
      var setup = List.of(
          command("setSimulationTime", "time", "1936-01-01T00:00"),
          command("putOrbatDocument", "document", Map.of("format", "orbat-native-v1", "documentId", "formation",
              "role", "actual", "root", Map.of("nodeId", "root", "name", "Division",
                  "marchSpeed", Map.of("kmPerHour", 6, "evidence", SOURCE)))),
          command("putUnit", "unit", Map.of("id", "division", "name", "Division", "provinceId", "west", "evidence", SOURCE,
              "actualRef", Map.of("documentId", "formation", "nodeId", "root"))));
      save(app, "main-0", null, 1, setup);
      var route = List.of("main-1/map/province/west", "main-1/map/province/east", "main-1/map/province/west");
      save(app, "main-1", null, 2, List.of(Map.of("op", "startMarch", "address", unit("main-1"), "route", route)));
      assertMarch(app, "main-2", "moving", 0, "west");

      var draft = open(app, "main-2", null);
      assertThrows(IllegalArgumentException.class, () -> app.apply(GM, draft, 0, "teleport", List.of(
          Map.of("op", "deployUnit", "unitId", "division", "provinceId", "east", "evidence", SOURCE)), true));
      var preview = app.apply(GM, draft, 0, "preview", List.of(command("advanceTime", "minutes", 60)), true);
      assertEquals("1936-01-01T01:00:00", json(preview).path("clock").path("time").asText());
      assertEquals(0L, preview.get("draftVersion"));
      assertEquals(0, march(app, "main-2").path("fraction").asDouble());
      app.apply(GM, draft, 0, "apply", List.of(command("advanceTime", "minutes", 60)), false);
      String baseHash = app.savedMap("main-2").stateHash();
      app.save(GM, draft, 1, "save-3", 3, baseHash);
      assertTrue((Boolean) app.save(GM, draft, 1, "save-3", 3, baseHash).get("replayedReceipt"));
      assertMarch(app, "main-3", "moving", 0, "west");
      JsonNode halfway = march(app, "main-3");
      assertTrue(halfway.path("point").path("x").asDouble() > 50);
      assertTrue(halfway.path("point").path("x").asDouble() < 150);

      save(app, "main-3", null, 4, List.of(command("advanceTime", "minutes", 60)));
      assertMarch(app, "main-4", "moving", 1, "east");
      save(app, "main-4", null, 5, List.of(command("advanceTime", "minutes", 120)));
      assertMarch(app, "main-5", "arrived", 2, "west");

      save(app, "main-3", "cancel", 4, List.of(command("cancelMarch", "address", unit("main-3")),
          command("advanceTime", "minutes", 300)));
      assertMarch(app, "cancel-4", "cancelled", 0, "west");
      assertEquals("1936-01-01T01:00:00", march(app, "cancel-4").path("cancelledAt").asText());
      assertEquals(halfway.path("fraction"), march(app, "cancel-4").path("fraction"));
      assertEquals(5, json(app.lineage("cancel")).path("nodes").size());
      assertEquals(halfway, march(app, "main-3"));
      expected = json(app.marches(app.savedMap("main-5")));
    }
    try (var store = FileTimelineStore.open(world)) {
      var app = new WorldApplicationService(store);
      assertEquals(expected, json(app.marches(app.savedMap("main-5"))));
      assertEquals("1936-01-01T04:00:00", json(app.lineage("main")).path("nodes").get(5).path("clock").path("time").asText());
      assertEquals(7, new TimelineService(store).verifyAll());
    }
  }

  @Test
  void opensAnUntimedNodeWithVerifiedDateEvidenceThenPersistsAdvancement(@TempDir Path world) throws Exception {
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), GM);
      var original = new WorldApplicationService(store);
      var draft = open(original, "main-0", null);
      assertEquals("SIMULATION_TIME_REQUIRED", assertThrows(TimelineException.class, () ->
          original.apply(GM, draft, 0, "missing-time", List.of(command("advanceTime", "minutes", 1)), true)).code());
      save(original, "main-0", null, 1, List.of(Map.of("op", "patchProvince", "id", "east", "name", "East II")));
      String firstHash = original.savedMap("main-1").stateHash();
      Files.writeString(world.resolve("scenario-calendar.json"), JsonCodec.canonicalJson(Map.of("formatVersion", 1,
          "anchors", List.of(Map.of("node", "main-1", "stateHash", firstHash, "date", "1936-01-01", "source", "fixture:date")))));

      var app = new WorldApplicationService(store);
      assertNull(app.clock(app.savedMap("main-0")));
      assertEquals("date", app.clock(app.savedMap("main-1")).precision());
      save(app, "main-1", null, 2, List.of(command("advanceTime", "minutes", 45)));
      assertEquals("1936-01-01T00:45:00", app.savedMap("main-2").state().simulationTime());
      assertEquals(root.stateHash(), app.savedMap("main-0").stateHash());
      assertEquals(firstHash, app.savedMap("main-1").stateHash());
    }
  }

  private static void assertMarch(WorldApplicationService app, String node, String status, int legs, String province) {
    JsonNode march = march(app, node);
    assertEquals(status, march.path("status").asText());
    assertEquals(legs, march.path("reachedLegs").asInt());
    assertEquals(node + "/map/province/" + province, march.path("provinceAddress").asText());
    assertEquals(march.path("provinceAddress").asText(), app.lookup(unit(node), false).get("provinceAddress"));
  }

  private static JsonNode march(WorldApplicationService app, String node) {
    return json(app.marches(app.savedMap(node))).path("marches").get(0);
  }

  private static JsonNode json(Object value) { return JsonCodec.mapper().valueToTree(value); }

  private static String unit(String node) { return node + "/orbat/unit/division"; }

  private static Map<String, Object> command(String op, String key, Object value) { return Map.of("op", op, key, value); }

  private static String open(WorldApplicationService app, String base, String branch) {
    return (String) app.open(GM, base, app.savedMap(base).stateHash(), branch).get("draftId");
  }

  private static void save(WorldApplicationService app, String base, String branch, long tick, List<Map<String, Object>> commands) {
    String draft = open(app, base, branch);
    app.apply(GM, draft, 0, "apply", commands, false);
    app.save(GM, draft, 1, "save-" + branch + "-" + tick, tick, app.savedMap(base).stateHash());
  }
}
