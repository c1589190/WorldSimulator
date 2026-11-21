package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldEventTimelineTest {
  private static final String GM = "gm:events";
  private static final String START = "1936-01-01T00:00:00";
  private static final String FIRST = "1936-01-01T00:01:15";
  private static final String SECOND = "1936-01-01T00:01:56";
  private static final String END = "1936-01-01T00:03:00";
  private static final Evidence PROOF = new Evidence("fixture:timed-arrival", "estimated", null, null);

  @Test
  void savesExactArrivalsAndReturnsTheSameSeriesAfterRestart(@TempDir Path path) {
    String hash;
    String draft;
    JsonNode nodes;
    try (var store = FileTimelineStore.create(path, SampleWorld.WORLD_ID)) {
      hash = new TimelineService(store).createGenesis(world(), GM).stateHash();
      var app = new WorldApplicationService(store);
      draft = open(app, "main-0", "events", true);
      var commands = List.of(advance(END));
      var preview = json(app.apply(GM, draft, 0, "preview", commands, true));
      assertEquals(List.of(FIRST, SECOND, END), preview.path("steps").valueStream().map(s -> s.path("time").asText()).toList());
      assertEquals(2, preview.path("steps").get(0).path("events").size());
      assertEquals(1, json(app.tree(0, 20)).path("nodes").size());
      app.apply(GM, draft, 0, "apply", commands, false);
      nodes = json(app.save(GM, draft, 1, "save", null, hash)).path("nodes");
      assertEquals(List.of("events-1", "events-2", "events-3"), nodes.valueStream().map(n -> n.path("node").asText()).toList());
      assertEquals("east", app.savedMap("events-1").state().formations().units().get("a").provinceId());
      assertEquals("west", app.savedMap("events-2").state().formations().units().get("a").provinceId());
      assertEquals(preview.path("stateHash").asText(), app.savedMap("events-3").stateHash());
      assertEquals(hash, app.savedMap("main-0").stateHash());

      String split = open(app, "main-0", "split", true);
      app.apply(GM, split, 0, "apply", List.of(advance(FIRST)), false);
      app.save(GM, split, 1, "split-first", null, hash);
      var next = app.open(GM, "split-1", app.savedMap("split-1").stateHash(), null);
      assertEquals(true, next.get("eventTimeline"));
      String nextDraft = (String) next.get("draftId");
      assertEquals("EVENT_TIME_ALREADY_COMMITTED", assertThrows(TimelineException.class, () ->
          app.apply(GM, nextDraft, 0, "same-time", List.of(Map.of("op", "patchProvince", "id", "west", "name", "Updated")), false)).code());
      app.apply(GM, nextDraft, 0, "apply", commands, false);
      var splitSave = app.save(GM, nextDraft, 1, "split-end", null, app.savedMap("split-1").stateHash());
      assertEquals(app.savedMap("events-3").stateHash(), splitSave.get("stateHash"));
    }
    try (var store = FileTimelineStore.open(path)) {
      var app = new WorldApplicationService(store);
      var retry = json(app.save(GM, draft, 1, "save", null, hash));
      assertTrue(retry.path("replayedReceipt").asBoolean());
      assertEquals(nodes, retry.path("nodes"));
      assertEquals(7, new TimelineService(store).verifyAll());
    }
  }

  @Test
  void sameTimeAuthorizationChangesAreEvaluatedBeforeArrivalEvenAcrossDraftEdits(@TempDir Path path) {
    try (var store = FileTimelineStore.create(path, SampleWorld.WORLD_ID)) {
      String hash = new TimelineService(store).createGenesis(world(), GM).stateHash();
      var app = new WorldApplicationService(store);
      String draft = open(app, "main-0", null, true);
      var arrival = json(app.apply(GM, draft, 0, "advance", List.of(advance(FIRST)), false));
      assertEquals("march-arrived", arrival.path("steps").get(0).path("events").get(0).path("type").asText());

      var revoke = new LinkedHashMap<String, Object>(Map.of("op", "setTransitRight",
          "grantorAddress", "main-0/country/B", "guestAddress", "main-0/country/A"));
      revoke.put("evidence", null);
      var blocked = json(app.apply(GM, draft, 1, "revoke", List.of(revoke, advance(END)), false));
      assertEquals(2, blocked.path("steps").size());
      var events = blocked.path("steps").get(0).path("events");
      assertEquals(List.of("SetTransitRight", "march-blocked", "march-blocked"),
          events.valueStream().map(e -> e.path("type").asText()).toList());
      app.save(GM, draft, 2, "save", null, hash);
      var state = app.savedMap("main-1").state();
      assertEquals(FIRST, state.simulationTime());
      assertEquals("west", state.formations().units().get("a").provinceId());
      assertEquals("blocked", state.formations().marches().get("a").progress(FIRST).status());
      assertEquals(3, new TimelineService(store).verifyAll());
    }
  }

  private static Map<String, Object> advance(String time) {
    return Map.of("op", "advanceTime", "to", time);
  }

  private static String open(WorldApplicationService app, String base, String branch, boolean events) {
    return (String) app.open(GM, base, app.savedMap(base).stateHash(), branch, events).get("draftId");
  }

  private static JsonNode json(Object value) {
    return JsonCodec.mapper().valueToTree(value);
  }

  private static StaticWorldState world() {
    var countries = CountryState.EMPTY
        .withCountry(new CountryState.Country("A", "A", List.of(), null, "west", PROOF))
        .withCountry(new CountryState.Country("B", "B", List.of(), null, "east", PROOF))
        .withTerritory(List.of("west"), "owner", new CountryState.Assignment("A", PROOF))
        .withTerritory(List.of("east"), "owner", new CountryState.Assignment("B", PROOF))
        .withTransitRight("B", "A", PROOF);
    var conditions = new March.Conditions("land-march-v1", "plains", true, 1,
        new March.Supply("unknown", null, null), 1, countries.entry("A", "east"), 4.0);
    var crossing = new March.Leg("west", "east", .1, 75, conditions);
    var formation = new OrbatState(Map.of(), Map.of(), Map.of(
        "a", new OrbatState.Unit("a", "A unit", null, "A", "west", null, null, PROOF, null),
        "b", new OrbatState.Unit("b", "B unit", null, "A", "west", null, null, PROOF, null)),
        Map.of("a", new March(START, 4, PROOF, List.of(crossing, new March.Leg("east", "west", .1, 41, conditions)), null),
            "b", new March(START, 4, PROOF, List.of(crossing), null)), Map.of());
    return new StaticWorldState(SampleWorld.WORLD_ID, SampleWorld.createMap(), countries, formation, START);
  }
}
