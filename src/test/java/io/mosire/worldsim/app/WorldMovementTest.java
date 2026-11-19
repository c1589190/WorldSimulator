package io.mosire.worldsim.app;

import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatDocument.MarchSpeed;
import io.mosire.worldsim.orbat.OrbatDocument.Node;
import io.mosire.worldsim.state.Evidence;
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

class WorldMovementTest {
  private static final String ACTOR = "gm:test";
  private static final Evidence KNOWN = new Evidence("fixture:movement", "known", null, null);
  private static final Evidence ESTIMATED = new Evidence("fixture:movement-estimate", "estimated", null, null);
  private static final List<String> ROUTE = List.of("main-1/map/province/west", "main-1/map/province/east");

  @Test
  void calculatesFromReferencedSubtreeAndReplaysTheSavedSpeed(@TempDir Path world) throws Exception {
    WorldMovement.Estimate expected;
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), ACTOR);
      var app = new WorldApplicationService(store);
      var armor = node("armor", new MarchSpeed(16, KNOWN));
      var infantry = node("infantry", new MarchSpeed(3.5, KNOWN));
      var engineers = node("engineers", new MarchSpeed(3.5, ESTIMATED));
      var division = node("division", null, armor, node("support", null, infantry, engineers));
      var unrelated = node("different-division", new MarchSpeed(1, KNOWN));
      save(app, root.stateHash(), List.of(
          document("formation", "actual", node("army", null, division, unrelated)),
          document("template", "ideal", node("ideal-division", new MarchSpeed(8, KNOWN))),
          Map.of("op", "putUnit", "unit", Map.of("id", "division", "name", "Division", "evidence", KNOWN,
              "provinceId", "west", "actualRef", Map.of("documentId", "formation", "nodeId", "division"),
              "idealRef", Map.of("documentId", "template", "nodeId", "ideal-division")))));

      String address = "main-1/orbat/unit/division";
      var uncalibrated = app.movementEstimate(address, null, ROUTE);
      assertEquals("actual", uncalibrated.role());
      assertEquals("estimated", uncalibrated.speed().status());
      assertEquals(3.5, uncalibrated.speed().kmPerHour());
      assertNull(uncalibrated.route().distanceKm());
      assertNull(uncalibrated.route().durationMinutes());
      assertEquals("unknown", uncalibrated.route().status());

      var reference = new WorldGeography.Reference("worldsim-geo-v1", "fixture", "main-0", root.stateHash(),
          200, 100, "fixture:calibration", List.of(new Vec2(-1, -1), new Vec2(1, -1), new Vec2(1, 1), new Vec2(-1, 1)),
          List.of(50.0, 180_000 / Math.PI, 0.0, 50.0, 0.0, -180_000 / Math.PI), 1,
          new WorldGeography.Check(3, 1, 1, 1), List.of(), List.of(), null, null);
      Files.writeString(world.resolve("geo-reference.json"), JsonCodec.canonicalJson(reference));
      var loop = List.of(ROUTE.getFirst(), ROUTE.getLast(), ROUTE.getFirst());
      expected = app.movementEstimate(address, null, loop);
      assertEquals(List.of("main-1/orbat/doc/formation/node/infantry", "main-1/orbat/doc/formation/node/engineers"),
          expected.speed().limitingUnits().stream().map(WorldMovement.Limit::address).toList());
      assertEquals(ESTIMATED, expected.speed().limitingUnits().getLast().evidence());
      assertEquals(2, expected.route().legs().size());
      assertEquals("estimated", expected.route().status());
      assertEquals("wgs84-great-circle", expected.route().distanceReference().method());
      assertEquals(22.239016, expected.route().distanceKm(), 1e-6);
      assertEquals(expected.route().distanceKm() / 3.5 * 60, expected.route().durationMinutes(), 1e-6);
      assertEquals(expected.route().durationMinutes() / 2, expected.route().legs().getFirst().durationMinutes(), 1e-6);
      var ideal = app.movementEstimate(address, "ideal", ROUTE);
      assertEquals(8.0, ideal.speed().kmPerHour());
      assertTrue(ideal.formationAddress().startsWith("main-1/orbat/doc/template/version/sha256:"));
      assertNull(app.movementEstimate(address, null, null).route());

      for (var invalid : List.of(List.of(ROUTE.getLast(), ROUTE.getFirst()),
          List.of(ROUTE.getFirst(), ROUTE.getFirst()), List.of(ROUTE.getFirst(), "main-0/map/province/east"))) {
        assertEquals("MOVEMENT_ROUTE_INVALID",
            assertThrows(TimelineException.class, () -> app.movementEstimate(address, null, invalid)).code());
      }
      assertEquals(2, app.tree(0, 10).get("total"));
    }
    try (var store = FileTimelineStore.open(world)) {
      var app = new WorldApplicationService(store);
      var loop = List.of(ROUTE.getFirst(), ROUTE.getLast(), ROUTE.getFirst());
      assertEquals(expected, app.movementEstimate("main-1/orbat/unit/division", null, loop));
      assertEquals(2, new TimelineService(store).verifyAll());
    }
  }

  @Test
  void distinguishesMissingSpeedFromImmobileUnits(@TempDir Path world) {
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), ACTOR);
      var app = new WorldApplicationService(store);
      var mobile = node("mobile", new MarchSpeed(5, KNOWN));
      var unreported = node("unreported", null);
      var disabled = node("disabled", new MarchSpeed(0, KNOWN));
      var hq = new Node("hq", "HQ", null, null, new OrbatDocument.Quantity(10, "known", KNOWN.source(), null),
          null, List.of(mobile), null);
      var immobile = node("immobile", null, disabled, unreported);
      save(app, root.stateHash(), List.of(document("staff", "actual", hq), unit("staff", "staff", "hq"),
          document("immobile", "actual", immobile), unit("immobile", "immobile", "immobile")));

      var unknown = app.movementEstimate("main-1/orbat/unit/staff", null, ROUTE);
      assertEquals("unknown", unknown.speed().status());
      assertNull(unknown.speed().kmPerHour());
      assertEquals(List.of("main-1/orbat/doc/staff/node/hq"), unknown.speed().missingSpeeds());
      assertNull(unknown.route().durationMinutes());
      var stationary = app.movementEstimate("main-1/orbat/unit/immobile", null, ROUTE);
      assertEquals(0.0, stationary.speed().kmPerHour());
      assertEquals("immobile", stationary.route().status());
      assertEquals(List.of("main-1/orbat/doc/immobile/node/unreported"), stationary.speed().missingSpeeds());
      assertNull(stationary.route().durationMinutes());
      assertThrows(IllegalArgumentException.class, () -> new MarchSpeed(-1, KNOWN));
      assertThrows(IllegalArgumentException.class, () -> new MarchSpeed(4, new Evidence(null, "unknown", null, null)));
    }
  }

  private static void save(WorldApplicationService app, String baseHash, List<Map<String, Object>> commands) {
    String draft = (String) app.open(ACTOR, "main-0", baseHash, null).get("draftId");
    app.apply(ACTOR, draft, 0, "movement", commands, false);
    app.save(ACTOR, draft, 1, "movement-save", 1, baseHash);
  }

  private static Node node(String id, MarchSpeed speed, Node... children) {
    return new Node(id, id, null, null, null, null, List.of(children), null, speed);
  }

  private static Map<String, Object> document(String id, String role, Node root) {
    return Map.of("op", "putOrbatDocument", "document", new OrbatDocument(OrbatDocument.FORMAT, id, role, null, root));
  }

  private static Map<String, Object> unit(String id, String document, String node) {
    return Map.of("op", "putUnit", "unit", Map.of("id", id, "name", id, "provinceId", "west", "evidence", KNOWN,
        "actualRef", Map.of("documentId", document, "nodeId", node)));
  }
}
