package io.mosire.worldsim.app;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CountryOrbatTimelineTest {
  private static final String ACTOR = "gm:test";
  private static final Evidence SOURCE = new Evidence("fixture:source", "known", null, "1936-01-01");

  @Test
  void committedDomainsSurviveRestartWithPinnedIdealsAndIndependentActuals(@TempDir Path path) {
    Path world = path.resolve("world");
    String originalHash;
    String savedHash;
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var initial = SampleWorld.create();
      record Legacy(String id, WorldMap map) {}
      assertEquals(JsonCodec.canonicalJson(new Legacy(initial.id(), initial.map())), initial.canonicalJson());
      originalHash = new TimelineService(store).createGenesis(initial, ACTOR).stateHash();
      var app = new WorldApplicationService(store);
      assertTrue(JsonCodec.canonicalJson(app.search("main-0", "West", "province", 0, 20))
          .contains("main-0/map/province/west"));
      String draft = (String) app.open(ACTOR, "main-0", originalHash, "scenario").get("draftId");
      var ideal = document("template", "ideal", 30);
      var actual = document("actual-a", "actual", 0);
      var second = Map.<String, Object>of("kind", "mil-org", "version", 1,
          "data", Map.of("name", "Second strength", "personnel", 0,
              "custom", Map.of("orbat", Map.of("source", "original"))));
      List<Map<String, Object>> batch = List.of(
          cmd("putCountry", "country", new CountryState.Country("POR", "Portugal", List.of("葡萄牙"), "#368455", "west", SOURCE)),
          cmd("putCountry", "country", new CountryState.Country("OTHER", "Other", List.of(), null, null, SOURCE)),
          territory("owner", "POR"),
          territory("controller", "OTHER"),
          cmd("putOrbatDocument", "document", ideal),
          cmd("putOrbatDocument", "document", actual),
          Map.of("op", "importMilOrg", "documentId", "actual-b", "role", "actual", "source", "fixture:dsh", "milOrg", second),
          cmd("putUnit", "unit", unit("a", "actual-a", "west")),
          cmd("putUnit", "unit", unit("b", "actual-b", "west")));
      var preview = app.apply(ACTOR, draft, 0, "preview", batch, true);
      assertEquals(0L, preview.get("draftVersion"));
      assertTrue(JsonCodec.canonicalJson(preview).contains("\"provinceIds\":[\"west\"]"));
      assertEquals(0, app.overview("main-0").get("countries"));
      app.apply(ACTOR, draft, 0, "apply", batch, false);
      String firstHash = (String) app.save(ACTOR, draft, 1, "save", 1, originalHash).get("stateHash");
      assertNotEquals(originalHash, firstHash);
      var province = app.lookup("scenario-1/map/province/west", false);
      var territory = (CountryState.Territory) province.get("territory");
      assertEquals("POR", territory.owner().countryId());
      assertEquals("OTHER", territory.controller().countryId());
      var markers = app.unitMarkers("scenario-1");
      assertEquals(2, markers.size());
      assertEquals(markers, province.get("units"));
      var marker = (WorldQueries.UnitMarker) markers.get(1);
      assertEquals("scenario-1/orbat/unit/b", marker.address());
      assertEquals("scenario-1/map/province/west", marker.provinceAddress());
      assertEquals("POR", marker.countryId());
      assertEquals("#368455", marker.countryColor());
      assertEquals("infantry", marker.type());
      assertEquals("company", marker.echelon());
      var first = app.lookup("scenario-1/orbat/unit/a", false);
      var secondUnit = app.lookup("scenario-1/orbat/unit/b", false);
      assertEquals(first.get("idealAddress"), secondUnit.get("idealAddress"));
      assertEquals(0, at(app, first.get("actualAddress")).personnel().value());
      assertTrue(JsonCodec.canonicalJson(first.get("directStrengthDifference")).contains("\"delta\":-30"));
      assertEquals("legacy-ambiguous", at(app, secondUnit.get("actualAddress")).personnel().status());
      assertFalse(secondUnit.containsKey("directStrengthDifference"));
      assertTrue(JsonCodec.canonicalJson(app.exportMilOrg((String) secondUnit.get("actualAddress")))
          .contains("incomplete-strength"));
      var imported = (OrbatDocument) app.lookup("scenario-1/orbat/doc/actual-b", false).get("entity");
      assertEquals("original", imported.root().extensions().get("dsh.mil-org").data()
          .path("custom").path("orbat").path("source").asText());
      assertEquals("Portugal", app.lookup("scenario-1/country/POR", false).get("displayName"));
      assertEquals(1, app.search("scenario-1", "葡萄牙", "country", 0, 30).get("total"));

      String advance = (String) app.open(ACTOR, "scenario-1", firstHash, null).get("draftId");
      var update = List.of(cmd("putOrbatDocument", "document", document("template", "ideal", 60)),
          territory("controller", "POR"),
          Map.<String, Object>of("op", "deployUnit", "unitId", "a", "provinceId", "east", "evidence", SOURCE));
      var change = app.apply(ACTOR, advance, 0, "replace", update, true);
      assertTrue(JsonCodec.canonicalJson(change).contains("\"conflictCount\":1"));
      app.apply(ACTOR, advance, 0, "advance", update, false);
      savedHash = (String) app.save(ACTOR, advance, 1, "save-next", 2, firstHash).get("stateHash");
    }
    try (var store = FileTimelineStore.open(world)) {
      var app = new WorldApplicationService(store);
      assertEquals(3, new TimelineService(store).verifyAll());
      assertEquals(originalHash, app.overview("main-0").get("stateHash"));
      assertEquals(savedHash, app.overview("scenario-2").get("stateHash"));
      var unit = app.lookup("scenario-2/orbat/unit/a", false);
      assertEquals("scenario-2/map/province/east", unit.get("provinceAddress"));
      assertEquals("scenario-1/map/province/west", app.lookup("scenario-1/orbat/unit/a", false).get("provinceAddress"));
      assertEquals(30, at(app, unit.get("idealAddress")).personnel().value());
      assertEquals(60, at(app, "scenario-2/orbat/doc/template/node/root").personnel().value());
      assertEquals(0, at(app, unit.get("actualAddress")).personnel().value());
      assertEquals("scenario-2", NodeAddress.parse((String) unit.get("idealAddress")).node().label());
    }
  }

  @Test
  void colocatedDivisionsProjectTwoMarkersAndKeepSeparateSubordinateTrees(@TempDir Path path) {
    try (var store = FileTimelineStore.create(path.resolve("world"), SampleWorld.WORLD_ID)) {
      var base = new TimelineService(store).createGenesis(SampleWorld.create(), ACTOR);
      var app = new WorldApplicationService(store);
      String draft = (String) app.open(ACTOR, "main-0", base.stateHash(), null).get("draftId");
      var batch = new ArrayList<Map<String, Object>>();
      for (String id : List.of("first", "second")) {
        var battalion = new OrbatDocument.Node("battalion", id + " battalion", "armored", "battalion",
            null, null, null, null);
        var regiment = new OrbatDocument.Node("regiment", id + " regiment", "armored", "regiment",
            null, null, List.of(battalion), null);
        var division = new OrbatDocument.Node("root", id + " division", "armored", "division",
            null, null, List.of(regiment), null);
        String documentId = id + "-formation";
        batch.add(cmd("putOrbatDocument", "document",
            new OrbatDocument(OrbatDocument.FORMAT, documentId, "actual", null, division)));
        batch.add(cmd("putUnit", "unit", new OrbatState.Unit(id, division.name(), null, null, "west",
            null, new OrbatState.Ref(documentId, "root", null), SOURCE, null)));
      }
      app.apply(ACTOR, draft, 0, "apply-divisions", batch, false);
      app.save(ACTOR, draft, 1, "save-divisions", 1, base.stateHash());

      var markers = app.unitMarkers("main-1");
      assertEquals(2, markers.size());
      assertEquals(markers, app.lookup("main-1/map/province/west", false).get("units"));
      var first = (WorldQueries.UnitMarker) markers.get(0);
      var second = (WorldQueries.UnitMarker) markers.get(1);
      assertEquals(first.point(), second.point());
      assertEquals(List.of("unit:first", "unit:second"), List.of(first.stableId(), second.stableId()));
      for (var marker : List.of(first, second)) {
        assertEquals("armored", marker.type());
        assertEquals("division", marker.echelon());
        var unit = (OrbatState.Unit) app.lookup(marker.address(), false).get("entity");
        var formation = at(app, "main-1/orbat/doc/" + unit.actualRef().documentId() + "/node/root");
        assertEquals(unit.id() + " regiment", formation.children().getFirst().name());
        assertEquals(unit.id() + " battalion", formation.children().getFirst().children().getFirst().name());
      }
    }
  }

  @Test
  void invalidCrossDomainReferencesDoNotMutateDraft(@TempDir Path path) {
    try (var store = FileTimelineStore.create(path.resolve("world"), SampleWorld.WORLD_ID)) {
      var base = new TimelineService(store).createGenesis(SampleWorld.create(), ACTOR);
      var app = new WorldApplicationService(store);
      assertThrows(IllegalArgumentException.class, () -> JsonCodec.fromJson(
          "{\"value\":1.5,\"status\":\"known\",\"source\":\"fixture:test\"}", OrbatDocument.Quantity.class));
      String draft = (String) app.open(ACTOR, "main-0", base.stateHash(), null).get("draftId");
      var invalid = assertThrows(TimelineException.class, () -> app.apply(ACTOR, draft, 0, "invalid", List.of(
          cmd("putCountry", "country", new CountryState.Country("POR", "Portugal", null, null, "west", SOURCE)),
          territory("owner", "MISSING")), false));
      assertEquals("TIMELINE_STATE_INVALID", invalid.code());
      assertEquals(0, app.overview("main-0").get("countries"));
      assertEquals(1L, app.apply(ACTOR, draft, 0, "valid", List.of(
          cmd("putCountry", "country", new CountryState.Country("POR", "Portugal", null, null, "west", SOURCE)),
          territory("owner", "POR")), false).get("draftVersion"));
    }
  }

  private static OrbatDocument document(String id, String role, int count) {
    return new OrbatDocument(OrbatDocument.FORMAT, id, role, null,
        new OrbatDocument.Node("root", id, "infantry", "company",
            new OrbatDocument.Quantity(count, "known", "fixture:source", null), null, null, null));
  }

  private static OrbatState.Unit unit(String id, String actual, String province) {
    return new OrbatState.Unit(id, "Unit " + id, "infantry", "POR", province,
        new OrbatState.Ref("template", "root", null), new OrbatState.Ref(actual, "root", null), SOURCE, null);
  }

  private static OrbatDocument.Node at(WorldApplicationService app, Object address) {
    return (OrbatDocument.Node) app.lookup((String) address, false).get("entity");
  }

  private static Map<String, Object> cmd(String op, String key, Object value) {
    return Map.of("op", op, key, value);
  }

  private static Map<String, Object> territory(String field, String country) {
    return Map.of("op", "setTerritory", "provinceIds", List.of("west"), "field", field,
        "assignment", new CountryState.Assignment(country, SOURCE));
  }
}
