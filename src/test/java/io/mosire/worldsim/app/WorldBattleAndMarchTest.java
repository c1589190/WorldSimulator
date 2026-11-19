package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.cli.SyntheticWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldBattleAndMarchTest {
  private static final String GM = "gm:test";
  private static final Map<String, String> SOURCE = Map.of("source", "fixture:combat", "status", "estimated");

  @Test
  void resolvesLossesAndControlAtomicallyAndRetainsUnreportedStrength(@TempDir Path world) {
    JsonNode record;
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), GM);
      var app = new WorldApplicationService(store);
      var rifles = Map.of("nodeId", "rifles", "name", "Rifles", "personnel", quantity(40),
          "equipment", Map.of("coverage", "partial", "items",
              List.of(Map.of("itemId", "rifle", "name", "Rifle", "quantity", quantity(12)))));
      var crew = Map.of("nodeId", "crew", "name", "Crew");
      save(app, 1, List.of(
          cmd("setSimulationTime", "time", "1936-01-01T09:00:00"), country("A", "west"), country("B", "east"),
          owner("A", List.of("west")), owner("B", List.of("east")),
          document("actual-a", "actual", Map.of("nodeId", "root", "name", "A", "children", List.of(rifles, crew))),
          document("ideal-a", "ideal", Map.of("nodeId", "root", "name", "Ideal", "personnel", quantity(60))),
          unit("a", "A", "west", "actual-a", "ideal-a"), unit("b", "B", "east", null, null)));

      var goodLoss = Map.of("unitAddress", unitAddress("main-1", "a"),
          "formationAddress", "main-1/orbat/doc/actual-a/node/rifles", "personnel", 9,
          "equipment", List.of(Map.of("itemId", "rifle", "count", 3)));
      var unknownLoss = Map.of("unitAddress", unitAddress("main-1", "a"),
          "formationAddress", "main-1/orbat/doc/actual-a/node/crew", "personnel", 2);
      var request = new LinkedHashMap<String, Object>(Map.of("id", "exercise", "provinceAddress", "main-1/map/province/east",
          "attackers", List.of(unitAddress("main-1", "a")), "defenders", List.of(unitAddress("main-1", "b")),
          "outcome", "attacker-victory", "rationale", "Fixture resolution", "evidence", SOURCE,
          "controllerAddress", "main-1/country/A", "losses", List.of(goodLoss, unknownLoss),
          "startedAt", "1936-01-01T08:15:00"));
      String draft = draft(app, 2);
      var oversized = new LinkedHashMap<>(goodLoss);
      oversized.put("personnel", 41);
      var invalid = new LinkedHashMap<>(request);
      invalid.put("losses", List.of(oversized));
      assertThrows(IllegalArgumentException.class, () ->
          app.apply(GM, draft, 0, "over-loss", List.of(cmd("resolveBattle", "battle", invalid)), false));
      var command = cmd("resolveBattle", "battle", request);
      assertEquals("BATTLE_ALREADY_RESOLVED", assertThrows(TimelineException.class, () ->
          app.apply(GM, draft, 0, "double-result", List.of(command, command), false)).code());
      var preview = json(app.apply(GM, draft, 0, "preview", List.of(command), true));
      assertEquals(0, preview.path("draftVersion").asInt());
      assertEquals(31, preview.path("battles").get(0).path("losses").get(0).path("personnel").path("after").asInt());
      assertEquals(40, strength(app, "main-1", "rifles").path("personnel").path("value").asInt());
      assertEquals(0, app.battles(app.savedMap("main-1"), 0, 25).get("total"));
      app.apply(GM, draft, 0, "apply", List.of(command), false);
      app.save(GM, draft, 1, "resolve", 2, app.savedMap("main-1").stateHash());
      record = json(app.lookup("main-2/orbat/battle/exercise", false));
      assertEquals("1936-01-01T09:00:00", record.path("entity").path("endedAt").asText());
      assertEquals(app.savedMap("main-1").stateHash(), record.path("entity").path("baseHash").asText());
      var loss = record.path("entity").path("losses").get(1).path("personnel");
      assertEquals(2, loss.path("lost").asInt());
      assertFalse(loss.has("after"));
      assertTrue(strength(app, "main-2", "crew").path("personnel").path("value").isNull());
      var currentRifles = strength(app, "main-2", "rifles");
      assertEquals(31, currentRifles.path("personnel").path("value").asInt());
      assertEquals(9, currentRifles.path("equipment").path("items").get(0).path("quantity").path("value").asInt());
      var pinned = app.lookup(unitAddress("main-2", "a"), false).get("idealAddress");
      assertEquals(60, json(app.lookup((String) pinned, false)).path("entity").path("personnel").path("value").asInt());
      var territory = json(app.lookup("main-2/map/province/east", false)).path("territory");
      assertEquals("B", territory.path("owner").path("countryId").asText());
      assertEquals("A", territory.path("controller").path("countryId").asText());
      save(app, 3, List.of(cmd("advanceTime", "minutes", 15)));
    }
    try (var store = FileTimelineStore.open(world)) {
      var app = new WorldApplicationService(store);
      assertEquals(record, json(app.battles(app.savedMap("main-2"), 0, 1)).path("battles").get(0));
      var markers = json(app.battleMarkers(app.savedMap("main-2")));
      assertEquals(1, markers.size());
      var marker = markers.get(0);
      assertEquals("battle:exercise", marker.path("stableId").asText());
      assertEquals(record.path("address"), marker.path("address"));
      assertEquals(record.path("point"), marker.path("point"));
      assertEquals(record.path("provinceAddress"), marker.path("provinceAddress"));
      assertTrue(app.battleMarkers(app.savedMap("main-1")).isEmpty());
      var after = app.savedMap("main-3");
      assertTrue(app.battleMarkers(after).isEmpty());
      assertEquals(1, app.battles(after, 0, 1).get("total"));
      assertEquals(4, new TimelineService(store).verifyAll());
    }
  }

  @Test
  void appliesTerrainAndSupplyAndStopsOnlyTheMarchWithRevokedEntry(@TempDir Path world) throws Exception {
    StaticWorldState initial = SyntheticWorld.create(5, 1);
    try (var store = FileTimelineStore.create(world, initial.id())) {
      var root = new TimelineService(store).createGenesis(initial, GM);
      var reference = new WorldGeography.Reference("worldsim-geo-v1", "test", "main-0", root.stateHash(),
          500, 100, SOURCE.get("source"), List.of(new Vec2(-1, -1), new Vec2(1, -1), new Vec2(1, 1), new Vec2(-1, 1)),
          List.of(50.0, 180_000 / Math.PI, 0.0, 50.0, 0.0, -180_000 / Math.PI), 1,
          new WorldGeography.Check(3, 1, 1, 1), List.of(), List.of(), null, null);
      Files.writeString(world.resolve("geo-reference.json"), JsonCodec.canonicalJson(reference));
      var app = new WorldApplicationService(store);
      var setup = new ArrayList<>(List.of(cmd("setSimulationTime", "time", "1936-01-01T00:00"),
          country("A", p(0)), country("B", p(4)), owner("A", List.of(p(0), p(1), p(2), p(3))), owner("B", List.of(p(4))),
          Map.<String, Object>of("op", "patchProvince", "id", p(1), "terrain", "forest"),
          Map.<String, Object>of("op", "patchProvince", "id", p(2), "terrain", "mountain"),
          cmd("putRailway", "railway", Map.of("id", "rail", "level", 1, "provincePath", List.of(p(0), p(1), p(2)))),
          cmd("putSupplyHub", "supplyHub", Map.of("id", "hub", "level", 1, "provinceId", p(2))),
          transit("main-0", "A", "B", SOURCE)));
      for (String id : List.of("a", "other")) {
        setup.add(document(id, "actual", Map.of("nodeId", "root", "name", id,
            "marchSpeed", Map.of("kmPerHour", 10, "evidence", SOURCE))));
        setup.add(unit(id, "A", p(0), id, null));
      }
      save(app, 1, setup);
      var route = route("main-1", 0, 1, 2, 3, 4);
      var first = app.movementEstimate(unitAddress("main-1", "a"), null, route).route();
      assertEquals("blocked", first.status());
      assertNull(first.durationMinutes());
      assertEquals("owner", first.legs().getLast().conditions().passage().basis());
      String badDraft = draft(app, 2);
      assertEquals("MARCH_TRANSIT_BLOCKED", assertThrows(TimelineException.class,
          () -> app.apply(GM, badDraft, 0, "blocked", List.of(march("main-1", "a", route)), true)).code());
      save(app, 2, List.of(transit("main-1", "B", "A", SOURCE), march("main-1", "a", route),
          march("main-1", "other", route("main-1", 0, 1))));
      var estimated = app.movementEstimate(unitAddress("main-2", "a"), null, route("main-2", 0, 1, 2, 3, 4)).route();
      assertEquals("estimated", estimated.status());
      assertEquals(7.5, estimated.legs().getFirst().conditions().effectiveSpeedKmPerHour());
      assertEquals("hub", estimated.legs().get(1).conditions().supply().hubId());
      assertEquals(.55, estimated.legs().get(1).conditions().terrainFactor());
      assertEquals("connected", estimated.legs().get(2).conditions().supply().status());
      assertEquals("granted", estimated.legs().getLast().conditions().passage().status());
      assertEquals(7.0, estimated.legs().getLast().conditions().effectiveSpeedKmPerHour());

      var revoke = transit("main-2", "B", "A", null);
      var advance = cmd("advanceTime", "minutes", 600);
      String movementDraft = draft(app, 3);
      app.apply(GM, movementDraft, 0, "preview-revoke", List.of(revoke, advance), true);
      var missing = new LinkedHashMap<>(revoke);
      missing.remove("evidence");
      assertEquals("COMMAND_INVALID", assertThrows(TimelineException.class, () ->
          app.apply(GM, movementDraft, 0, "invalid-revoke", List.of(missing, advance), false)).code());
      app.apply(GM, movementDraft, 0, "apply", List.of(revoke, advance), false);
      app.save(GM, movementDraft, 1, "block-save", 3, app.savedMap("main-2").stateHash());
      var marches = json(app.marches(app.savedMap("main-3"))).path("marches");
      assertEquals("blocked", marches.get(0).path("status").asText());
      assertEquals(3, marches.get(0).path("reachedLegs").asInt());
      assertEquals("main-3/map/province/" + p(3), marches.get(0).path("provinceAddress").asText());
      assertEquals("B", marches.get(0).path("blockage").path("passage").path("authorityCountryId").asText());
      assertEquals("arrived", marches.get(1).path("status").asText());
      assertEquals("connected", json(app.supplyStatus(unitAddress("main-3", "a"))).path("supply").path("status").asText());
      save(app, 4, List.of(cmd("removeRailway", "railwayId", "rail"),
          Map.of("op", "setTerritory", "provinceIds", List.of(p(4)), "field", "controller", "assignment",
              Map.of("countryId", "A", "evidence", SOURCE))));
      assertEquals("disconnected", json(app.supplyStatus(unitAddress("main-4", "a"))).path("supply").path("status").asText());
      var permitted = app.movementEstimate(unitAddress("main-4", "a"), null, route("main-4", 3, 4)).route();
      assertEquals("domestic", permitted.legs().getFirst().conditions().passage().status());
      assertEquals("controller", permitted.legs().getFirst().conditions().passage().basis());
      assertEquals(5, new TimelineService(store).verifyAll());
    }
  }

  private static JsonNode strength(WorldApplicationService app, String node, String id) {
    return json(app.lookup(node + "/orbat/doc/actual-a/node/" + id, false)).path("entity");
  }

  private static JsonNode json(Object value) { return JsonCodec.mapper().valueToTree(value); }
  private static Map<String, Object> cmd(String op, String key, Object value) { return Map.of("op", op, key, value); }
  private static String unitAddress(String node, String id) { return node + "/orbat/unit/" + id; }
  private static String p(int i) { return "p-0-" + i; }
  private static List<String> route(String node, int... points) {
    return java.util.Arrays.stream(points).mapToObj(point -> node + "/map/province/" + p(point)).toList();
  }
  private static Map<String, Object> march(String node, String id, List<String> route) {
    return Map.of("op", "startMarch", "address", unitAddress(node, id), "route", route);
  }
  private static Map<String, Object> transit(String node, String grantor, String guest, Map<String, String> evidence) {
    var command = new LinkedHashMap<String, Object>(Map.of("op", "setTransitRight", "grantorAddress",
        node + "/country/" + grantor, "guestAddress", node + "/country/" + guest));
    command.put("evidence", evidence);
    return command;
  }
  private static Map<String, Object> country(String id, String capital) {
    return cmd("putCountry", "country", Map.of("id", id, "name", id, "capitalProvinceId", capital, "evidence", SOURCE));
  }
  private static Map<String, Object> owner(String country, List<String> provinces) {
    return Map.of("op", "setTerritory", "provinceIds", provinces, "field", "owner",
        "assignment", Map.of("countryId", country, "evidence", SOURCE));
  }
  private static Map<String, Object> quantity(int value) {
    return Map.of("value", value, "status", "known", "source", SOURCE.get("source"));
  }
  private static Map<String, Object> document(String id, String role, Map<String, Object> root) {
    return cmd("putOrbatDocument", "document", Map.of("format", "orbat-native-v1", "documentId", id, "role", role, "root", root));
  }
  private static Map<String, Object> unit(String id, String country, String province, String actual, String ideal) {
    var unit = new LinkedHashMap<String, Object>(Map.of("id", id, "name", id, "countryId", country,
        "provinceId", province, "evidence", SOURCE));
    if (actual != null) unit.put("actualRef", Map.of("documentId", actual, "nodeId", "root"));
    if (ideal != null) unit.put("idealRef", Map.of("documentId", ideal, "nodeId", "root"));
    return cmd("putUnit", "unit", unit);
  }
  private static String draft(WorldApplicationService app, int tick) {
    String base = "main-" + (tick - 1);
    return (String) app.open(GM, base, app.savedMap(base).stateHash(), null).get("draftId");
  }
  private static void save(WorldApplicationService app, int tick, List<Map<String, Object>> commands) {
    String base = "main-" + (tick - 1);
    String draft = draft(app, tick);
    app.apply(GM, draft, 0, "apply", commands, false);
    app.save(GM, draft, 1, "save-" + tick, tick, app.savedMap(base).stateHash());
  }
}
