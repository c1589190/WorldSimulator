package io.mosire.worldsim.app;

import io.mosire.worldsim.app.WorldMcpProcessTest.Client;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldCivicTurnMcpTest {
  private static final Map<String, Object> EVIDENCE = Map.of("source", "fixture:civic-turn", "status", "known");
  private static final String TURN = "main-1/turn/round1";

  @Test
  void separateCountriesSubmitAgainstOneOpeningAndGmPublishesReplayableCivicChange(@TempDir Path dir) throws Exception {
    try (var store = FileTimelineStore.create(dir.resolve("world"), SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    String hash;
    try (var gm = new Client(dir)) {
      gm.initialize();
      var draft = open(gm, "main-0");
      var opening = List.of(
          Map.<String, Object>of("op", "setSimulationTime", "time", "1936-01-01T00:00:00"),
          country("A"), country("B"),
          Map.<String, Object>of("op", "setPolitics", "countryId", "A", "politics",
              Map.of("regime", "republic", "government", "cabinet", "mobilization", "reserve", "evidence", EVIDENCE)),
          control("west", "A"), control("east", "A"),
          Map.<String, Object>of("op", "putCensus", "records", List.of(Map.of("provinceId", "west", "date", "1936-01-01",
              "basis", "resident", "people", 1234, "evidence", EVIDENCE))),
          Map.<String, Object>of("op", "putTreaty", "treaty", Map.of("id", "pact", "type", "ceasefire", "first", "B", "second", "A",
              "startsAt", "1936-01-01T00:00:00", "endsAt", "1936-01-01T12:00:00", "terms", "Exercise", "evidence", EVIDENCE)),
          info("private-a", List.of("A")), info("private-b", List.of("B")), info("gm-only", List.of()),
          Map.<String, Object>of("op", "openTurn", "brief", Map.of("id", "round1",
              "access", Map.of("A", List.of("map/province/west", "country/A"), "B", List.of("map/province/east", "country/B")))));
      apply(gm, draft, "opening", opening);
      var saved = save(gm, draft, "save-opening");
      hash = saved.path("stateHash").asText();
      assertEquals("main-1", saved.path("node").asText());
      var population = gm.tool("world_population", population("main-1/country/A"));
      assertEquals(1234, population.path("recordedPeople").asInt());
      assertEquals(1, population.path("missingProvinces").asInt());
      assertEquals("active", gm.tool("world_lookup", Map.of("address", "main-1/diplomacy/treaty/pact")).path("status").asText());
      assertEquals("republic", gm.tool("world_lookup", Map.of("address", "main-1/country/A")).path("politics").path("regime").asText());
    }

    for (String country : List.of("A", "B")) {
      try (var dm = new Client(dir, "mcp", "--decision-turn", TURN, "--decision-country", country)) {
        dm.initialize();
        var tools = dm.call("tools/list", Map.of()).path("tools").valueStream().map(t -> t.path("name").asText()).sorted().toList();
        assertEquals(List.of("decision_brief", "decision_lookup", "decision_submit"), tools);
        var brief = dm.tool("decision_brief", Map.of());
        assertEquals(hash, brief.path("baseHash").asText());
        assertEquals(1, brief.path("entries").size());
        assertEquals("private-" + country.toLowerCase(java.util.Locale.ROOT), brief.path("entries").get(0).path("title").asText());
        String own = country.equals("A") ? "west" : "east";
        String other = country.equals("A") ? "east" : "west";
        assertEquals(own, dm.tool("decision_lookup", Map.of("address", "main-1/map/province/" + own)).path("id").asText());
        denied(dm, "decision_lookup", Map.of("address", "main-1/map/province/" + other));
        denied(dm, "decision_lookup", Map.of("address", "main-0/map/province/" + own));
        denied(dm, "decision_lookup", Map.of("address", "main-1/info/brief/gm-only"));
        var submission = Map.<String, Object>of("text", "Proposal " + country, "rationale", "Exercise", "targets",
            List.of("main-1/map/province/" + own));
        denied(dm, "decision_submit", Map.of("text", "Spoof", "rationale", "Exercise", "countryId", "B"));
        denied(dm, "decision_submit", Map.of("text", "Read", "rationale", "Exercise", "targets", List.of("main-1/map/province/" + other)));
        var receipt = dm.tool("decision_submit", submission);
        assertFalse(receipt.path("replayedReceipt").asBoolean());
        assertEquals(receipt.path("receipt"), dm.tool("decision_submit", submission).path("receipt"));
      }
    }

    try (var gm = new Client(dir)) {
      gm.initialize();
      assertEquals(2, gm.tool("world_turn", Map.of("address", TURN)).path("received").size());
      var draft = open(gm, "main-1");
      apply(gm, draft, "settle", List.of(Map.of("op", "advanceTime", "to", "1936-01-02T00:00:00"),
          control("west", "B"), Map.of("op", "resolveTurn", "turnAddress", TURN, "resolution", "Exercise resolution")));
      var saved = save(gm, draft, "save-resolution");
      assertEquals("main-3", saved.path("node").asText());
      assertEquals("treaty-expired", saved.path("nodes").get(0).path("events").get(0).path("type").asText());
    }

    try (var gm = new Client(dir)) {
      gm.initialize();
      var turn = gm.tool("world_turn", Map.of("address", "main-3/turn/round1")).path("turn");
      assertEquals("Exercise resolution", turn.path("resolution").asText());
      assertEquals(2, turn.path("proposals").size());
      assertEquals(1234, gm.tool("world_population", population("main-3/country/B")).path("recordedPeople").asInt());
      assertEquals("expired", gm.tool("world_lookup", Map.of("address", "main-3/diplomacy/treaty/pact")).path("status").asText());
      assertEquals(6, gm.tool("timeline_verify", Map.of()).path("verifiedNodes").asInt());
    }
  }

  @Test
  void threeDecisionMakersSeeOnlyTheirBriefsAndUseSeparateProposalPermissions(@TempDir Path dir) throws Exception {
    try (var store = FileTimelineStore.create(dir.resolve("world"), SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    String turn = "main-1/turn/panel-round";
    String openingHash;
    try (var gm = new Client(dir)) {
      gm.initialize();
      var draft = open(gm, "main-0");
      apply(gm, draft, "open-v2", List.of(
          Map.of("op", "setSimulationTime", "time", "1936-01-01T00:00:00"), country("A"), country("B"),
          control("west", "B"),
          Map.of("op", "setTerritory", "provinceIds", List.of("west"), "field", "owner",
              "assignment", Map.of("countryId", "A", "evidence", EVIDENCE)),
          Map.of("op", "putCensus", "records", List.of(
              Map.of("provinceId", "west", "date", "1935-01-01", "basis", "resident", "people", 1234, "evidence", EVIDENCE),
              Map.of("provinceId", "west", "date", "1936-01-02", "basis", "resident", "people", 2345, "evidence", EVIDENCE))),
          unit("first"), unit("second"),
          briefFor("cabinet"), briefFor("command1"), briefFor("command2"), info("legacy-brief", List.of("A")),
          Map.of("op", "openTurn", "brief", Map.of("id", "panel-round", "participants", Map.of(
              "cabinet", seat("country", "A"),
              "command1", seat("orbat/unit", "first"),
              "command2", seat("orbat/unit", "second"))))));
      openingHash = save(gm, draft, "save-v2").path("stateHash").asText();
    }

    var subjects = Map.of("cabinet", "country/A", "command1", "orbat/unit/first", "command2", "orbat/unit/second");
    for (var participant : subjects.entrySet()) {
      try (var dm = new Client(dir, "mcp", "--decision-turn", turn, "--decision-maker", participant.getKey())) {
        dm.initialize();
        var brief = dm.tool("decision_brief", Map.of());
        assertEquals(participant.getKey(), brief.path("decisionMakerId").asText());
        assertEquals("main-1/" + participant.getValue(), brief.path("subjectAddress").asText());
        assertEquals(1, brief.path("entries").size());
        assertEquals(participant.getKey(), brief.path("entries").get(0).path("title").asText());
        assertEquals(openingHash, brief.path("baseHash").asText());
        assertFalse(dm.tool("decision_lookup", Map.of("address", "main-1/map/province/west")).isEmpty());
        denied(dm, "decision_submit", Map.of("text", "outside", "rationale", "test", "targets", List.of("main-1/map/province/west")));
        var deniedUnit = participant.getKey().equals("command2") ? "first" : "second";
        denied(dm, "decision_lookup", Map.of("address", "main-1/orbat/unit/" + deniedUnit));
        var args = Map.of("text", participant.getKey() + " decision", "rationale", "test",
            "targets", List.of("main-1/" + participant.getValue()));
        var receipt = dm.tool("decision_submit", args);
        assertEquals(receipt.path("receipt"), dm.tool("decision_submit", args).path("receipt"));
      }
    }

    try (var store = FileTimelineStore.open(dir.resolve("world"));
         var viewer = io.mosire.worldsim.app.viewer.WorldViewerServer.start(new WorldApplicationService(store), 0)) {
      var openCountry = http(viewer.port(), "/api/decisions", "main-1", openingHash, "country/A");
      assertEquals("receiving", openCountry.path("mode").asText());
      assertEquals("received", openCountry.path("decisions").get(0).path("status").asText());
      var openProvince = http(viewer.port(), "/api/province", "main-1", openingHash, "map/province/west");
      assertEquals("main-1/country/A", openProvince.path("ownerAddress").asText());
      assertEquals("main-1/country/B", openProvince.path("controllerAddress").asText());
      var census = http(viewer.port(), "/api/province/population", "main-1", openingHash, "map/province/west").path("records");
      assertEquals(1, census.size());
      assertEquals(1234, census.get(0).path("people").asInt());
    }

    String closedHash;
    try (var gm = new Client(dir)) {
      gm.initialize();
      assertEquals(3, gm.tool("world_turn", Map.of("address", turn)).path("received").size());
      var draft = open(gm, "main-1");
      var ruling = Map.of("id", "cabinet-note", "title", "Cabinet proposal", "text", "GM writes\na second line",
          "decisionMakerId", "cabinet", "disposition", "modified");
      var independent = Map.of("id", "general-note", "title", "Round record", "text", "Independent ruling");
      var invalid = Map.of("id", "invalid", "title", "Unknown decision", "text", "invalid",
          "decisionMakerId", "missing", "disposition", "accepted");
      denied(gm, "edit_apply", Map.of("draftId", draft.id(), "expectedVersion", 0, "requestId", "invalid-ruling",
          "commands", List.of(Map.of("op", "advanceTime", "to", "1936-01-02T00:00:00"),
              Map.of("op", "resolveTurn", "turnAddress", turn, "resolution", "GM closes", "rulings", List.of(invalid)))));
      apply(gm, draft, "resolve-v2", List.of(Map.of("op", "advanceTime", "to", "1936-01-02T00:00:00"),
          Map.of("op", "resolveTurn", "turnAddress", turn, "resolution", "GM closes the round", "rulings", List.of(ruling, independent))));
      closedHash = save(gm, draft, "save-resolution-v2").path("stateHash").asText();
      assertEquals(2, gm.tool("world_turn", Map.of("address", "main-2/turn/panel-round")).path("turn").path("rulings").size());
      assertEquals(6, gm.tool("timeline_verify", Map.of()).path("verifiedNodes").asInt());
    }
    try (var store = FileTimelineStore.open(dir.resolve("world"));
         var viewer = io.mosire.worldsim.app.viewer.WorldViewerServer.start(new WorldApplicationService(store), 0)) {
      for (var participant : subjects.entrySet()) {
        var archive = http(viewer.port(), "/api/decisions", "main-2", closedHash, participant.getValue());
        assertEquals("settled", archive.path("mode").asText());
        assertEquals(1, archive.path("decisions").size());
        assertEquals(participant.getKey() + " decision", archive.path("decisions").get(0).path("proposal").path("text").asText());
        var notes = archive.path("decisions").get(0).path("rulings");
        assertEquals(participant.getKey().equals("cabinet") ? 1 : 0, notes.size());
        if (notes.size() == 1) assertEquals("GM writes\na second line", notes.get(0).path("text").asText());
      }
      var round = http(viewer.port(), "/api/turn/rulings", "main-2", closedHash, null);
      assertEquals("GM closes the round", round.path("resolution").asText());
      assertEquals("main-2/turn/panel-round", round.path("resolvedAddress").asText());
      assertEquals("main-2/country/A", round.path("rulings").get(0).path("subjectAddress").asText());
      assertEquals("general-note", round.path("rulings").get(1).path("entry").path("id").asText());
      assertFalse(round.path("rulings").get(1).has("subjectAddress"));
      var historical = http(viewer.port(), "/api/decisions", "main-1", openingHash, "country/A");
      assertEquals("historical", historical.path("mode").asText());
      assertFalse(historical.path("decisions").get(0).has("proposal"));
      assertEquals(0, http(viewer.port(), "/api/turn/rulings", "main-1", openingHash, null).path("rulings").size());
      assertEquals(0, http(viewer.port(), "/api/province/population", "main-2", closedHash, "map/province/east").path("records").size());
    }
  }

  @Test
  void scopedRoundPublishesReportsAndKeepsEnemyTruthOutOfDiscovery(@TempDir Path dir) throws Exception {
    var world = dir.resolve("world");
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
      var calibration = new WorldGeography.Reference("worldsim-geo-v1", "fixture", "main-0", root.stateHash(),
          200, 100, "fixture", List.of(new io.mosire.worldsim.map.Vec2(-1, -1), new io.mosire.worldsim.map.Vec2(1, -1),
              new io.mosire.worldsim.map.Vec2(1, 1), new io.mosire.worldsim.map.Vec2(-1, 1)),
          List.of(50.0, 180_000 / Math.PI, 0.0, 50.0, 0.0, -180_000 / Math.PI), 1,
          new WorldGeography.Check(3, 1, 1, 1), List.of(), List.of(), null, null);
      java.nio.file.Files.writeString(world.resolve("geo-reference.json"), io.mosire.worldsim.json.JsonCodec.canonicalJson(calibration));
    }
    var staff = seat("orbat/command", "army-a");
    var army = new java.util.HashMap<>(seat("orbat/unit", "first"));
    army.put("contactRecipients", List.of("staff"));
    var reserve = new java.util.HashMap<>(seat("orbat/unit", "first"));
    reserve.put("readablePaths", List.of("orbat/unit/second"));
    var participants = Map.of("staff", staff, "cabinet", seat("country", "A"), "army", army,
        "other", seat("country", "B"), "reserve", reserve);
    try (var gm = new Client(dir)) {
      gm.initialize();
      var draft = open(gm, "main-0");
      apply(gm, draft, "visibility-open", List.of(
          Map.of("op", "setSimulationTime", "time", "1936-01-01T00:00:00"), country("A"), country("B"),
          control("west", "A"), control("east", "A"),
          Map.of("op", "putOrbatDocument", "document", Map.of("format", "orbat-native-v1", "documentId", "formation",
              "role", "actual", "root", Map.of("nodeId", "root", "name", "rehearsal",
                  "marchSpeed", Map.of("kmPerHour", 6, "evidence", EVIDENCE)))),
          Map.of("op", "putUnit", "unit", Map.of("id", "first", "name", "First formation", "countryId", "A",
              "provinceId", "west", "actualRef", Map.of("documentId", "formation", "nodeId", "root"), "evidence", EVIDENCE)),
          unit("second"),
          Map.of("op", "putHeadquarters", "headquarters", Map.of("id", "general-a", "name", "General A", "kind", "general-staff", "countryId", "A")),
          Map.of("op", "putHeadquarters", "headquarters", Map.of("id", "army-a", "name", "Army A", "kind", "army-staff",
              "countryId", "A", "parentId", "general-a", "unitIds", List.of("first", "second"))),
          document("private-a", "core", List.of()), document("public-a", "public", List.of()), document("shared-a", "shared", List.of("B")),
          Map.of("op", "putMapFeature", "feature", Map.of("id", "west-port", "kind", "port", "name", "West port",
              "provinceId", "west", "evidence", EVIDENCE)),
          Map.of("op", "putMapFeature", "feature", Map.of("id", "west-resource", "kind", "resource", "name", "West resource",
              "provinceId", "west", "commodity", "coal", "evidence", EVIDENCE)),
          Map.of("op", "putContact", "contact", Map.of("id", "observed", "owner", "gm", "title", "GM report", "text", "Exercise",
              "observedAt", "1936-01-01T00:00:00", "provinceId", "east", "recipients", List.of("army"), "evidence", EVIDENCE)),
          Map.of("op", "putContact", "contact", Map.of("id", "staff-only", "owner", "gm", "title", "Staff report", "text", "Exercise",
              "observedAt", "1936-01-01T00:00:00", "provinceId", "east", "recipients", List.of("staff"), "evidence", EVIDENCE)),
          Map.of("op", "openTurn", "brief", Map.of("id", "send", "scoped", true, "participants", participants))));
      assertEquals("main-1", save(gm, draft, "visibility-save").path("node").asText());
    }
    for (String id : participants.keySet()) {
      try (var dm = new Client(dir, "mcp", "--decision-turn", "main-1/turn/send", "--decision-maker", id)) {
        dm.initialize();
        var docs = dm.tool("decision_brief", Map.of()).path("documents");
        assertEquals(id.equals("cabinet"), docs.toString().contains("private-a"));
        assertEquals(id.equals("cabinet") || id.equals("other"), docs.toString().contains("shared-a"));
        var markers = dm.tool("decision_markers", Map.of());
        assertEquals(id.equals("army"), markers.toString().contains("GM report"));
        var lookup = dm.tool("decision_search", Map.of("query", "First formation"));
        assertEquals(List.of("staff", "army", "reserve").contains(id) ? 1 : 0, lookup.path("total").asInt());
        if (id.equals("other")) {
          denied(dm, "decision_lookup", Map.of("address", "main-1/orbat/unit/first"));
          denied(dm, "decision_lookup", Map.of("address", "main-1/info/document/private-a"));
          denied(dm, "decision_submit", Map.of("text", "wrong", "rationale", "test",
              "movements", List.of(Map.of("unitId", "first", "destinationId", "east"))));
        }
        var args = new java.util.HashMap<String, Object>(Map.of("text", "proposal", "rationale", "test"));
        if (id.equals("army")) {
          args.put("movements", List.of(Map.of("unitId", "first", "destinationId", "east")));
          args.put("reports", List.of(Map.of("key", "report1", "title", "Field report", "text", "Exercise",
              "observedAt", "1936-01-01T00:00:00", "provinceId", "east", "recipients", List.of("staff"), "evidence", EVIDENCE)));
        }
        dm.tool("decision_submit", args);
      }
    }
    String arrived;
    try (var gm = new Client(dir)) {
      gm.initialize();
      var plan = gm.tool("world_plan_land_route", Map.of("address", "main-1/orbat/unit/first",
          "destination", "main-1/map/province/east"));
      assertEquals(2, plan.path("route").size());
      var step = open(gm, "main-1");
      apply(gm, step, "publish", List.of(
          Map.of("op", "advanceTime", "minutes", 1),
          Map.of("op", "resolveTurn", "turnAddress", "main-1/turn/send", "resolution", "Approved exercise movement"),
          Map.of("op", "startMarch", "address", "main-1/orbat/unit/first",
              "route", plan.path("route").valueStream().map(n -> n.asText()).toList())));
      String moving = save(gm, step, "publish-save").path("node").asText();
      var advance = open(gm, moving);
      apply(gm, advance, "advance", List.of(Map.of("op", "advanceTime", "minutes", 180),
          Map.of("op", "openTurn", "brief", Map.of("id", "read", "scoped", true, "participants", participants))));
      arrived = save(gm, advance, "advance-save").path("node").asText();
      assertEquals(arrived + "/map/province/east", gm.tool("world_lookup", Map.of("address",
          arrived + "/orbat/unit/first")).path("provinceAddress").asText());
      assertEquals("PASS", gm.tool("timeline_verify", Map.of()).path("result").asText());
    }
    for (String id : List.of("staff", "other")) {
      try (var dm = new Client(dir, "mcp", "--decision-turn", arrived + "/turn/read", "--decision-maker", id)) {
        dm.initialize();
        assertEquals(id.equals("staff"), dm.tool("decision_markers", Map.of()).toString().contains("Field report"));
      }
    }
    try (var store = FileTimelineStore.open(world)) {
      var app = new WorldApplicationService(store);
      try (var viewer = io.mosire.worldsim.app.viewer.WorldViewerServer.start(app, 0)) {
        var saved = app.savedMap(arrived);
        var seats = http(viewer.port(), "/api/unit-perspective", arrived, saved.stateHash(), "orbat/unit/first");
        assertEquals(List.of("army", "reserve"), seats.valueStream().map(row -> row.path("id").asText()).toList());
        assertEquals(List.of(arrived + "/orbat/unit/first"),
            seats.get(0).path("units").valueStream().map(n -> n.asText()).toList());
        var contacts = seats.get(0).path("contacts").valueStream().map(n -> n.asText()).toList();
        assertEquals(2, contacts.size());
        assertTrue(contacts.contains(arrived + "/info/contact/observed"));
        assertFalse(contacts.contains(arrived + "/info/contact/staff-only"));
        assertEquals(2, seats.get(1).path("units").size());
        assertTrue(seats.get(1).path("contacts").isEmpty());
        assertTrue(http(viewer.port(), "/api/unit-perspective", arrived, saved.stateHash(), "orbat/unit/second").isEmpty());
      }
    }
  }

  private static Map<String, Object> document(String id, String scope, List<String> countries) {
    return Map.of("op", "putIntelligenceDocument", "intelligenceDocument",
        Map.of("id", id, "countryId", "A", "title", id, "text", "Exercise document", "scope", scope,
            "countries", countries, "evidence", EVIDENCE));
  }

  private static Map<String, Object> seat(String kind, String id) {
    String path = kind + "/" + id;
    return Map.of("displayName", id, "subject", Map.of("kind", kind, "id", id),
        "readablePaths", List.of(path, "map/province/west"), "proposalTargets", List.of(path));
  }

  private static Map<String, Object> briefFor(String id) {
    return Map.of("op", "putInfo", "entry", Map.of("pack", "v2", "id", id, "title", id,
        "text", "receipt test", "decisionAudience", List.of(id), "evidence", EVIDENCE));
  }

  private static Map<String, Object> unit(String id) {
    return Map.of("op", "putUnit", "unit", Map.of("id", id, "name", id, "countryId", "A", "provinceId", "west", "evidence", EVIDENCE));
  }

  private static com.fasterxml.jackson.databind.JsonNode http(int port, String path, String node, String hash, String entity) throws Exception {
    var url = java.net.URI.create("http://127.0.0.1:" + port + path + "?node=" + node + "&hash=" + hash
        + (entity == null ? "" : "&address=" + node + "/" + entity));
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      var response = client.send(java.net.http.HttpRequest.newBuilder(url).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return io.mosire.worldsim.json.JsonCodec.mapper().readTree(response.body());
    }
  }

  private static Map<String, Object> country(String id) {
    return Map.of("op", "putCountry", "country", Map.of("id", id, "name", id, "evidence", EVIDENCE));
  }

  private static Map<String, Object> control(String province, String country) {
    return Map.of("op", "setTerritory", "provinceIds", List.of(province), "field", "controller",
        "assignment", Map.of("countryId", country, "evidence", EVIDENCE));
  }

  private static Map<String, Object> info(String name, List<String> audience) {
    return Map.of("op", "putInfo", "entry", Map.of("pack", "brief", "id", name, "title", name, "text", "Exercise",
        "type", "test", "payload", Map.of("level", 1), "audience", audience, "evidence", EVIDENCE));
  }

  private static Map<String, Object> population(String scope) {
    return Map.of("scope", scope, "date", "1936-01-01", "basis", "resident");
  }

  private record Draft(String id, String hash) {}

  private static Draft open(Client gm, String base) throws Exception {
    String hash = gm.tool("world_overview", Map.of("node", base)).path("stateHash").asText();
    String id = gm.tool("edit_open", Map.of("base", base, "expectedHash", hash, "eventTimeline", true)).path("draftId").asText();
    return new Draft(id, hash);
  }

  private static void apply(Client gm, Draft draft, String request, List<Map<String, Object>> commands) throws Exception {
    assertEquals(1, gm.tool("edit_apply", Map.of("draftId", draft.id(), "expectedVersion", 0, "requestId", request,
        "commands", commands)).path("draftVersion").asInt());
  }

  private static com.fasterxml.jackson.databind.JsonNode save(Client gm, Draft draft, String request) throws Exception {
    return gm.tool("edit_save", Map.of("draftId", draft.id(), "expectedVersion", 1, "requestId", request, "baseHash", draft.hash()));
  }

  private static void denied(Client dm, String tool, Map<String, Object> args) throws Exception {
    var result = dm.call("tools/call", Map.of("name", tool, "arguments", args));
    assertTrue(result.path("isError").asBoolean(), result.toString());
  }
}
