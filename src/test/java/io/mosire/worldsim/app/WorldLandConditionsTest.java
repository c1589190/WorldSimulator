package io.mosire.worldsim.app;

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

class WorldLandConditionsTest {
  private static final String GM = "gm:terrain";
  private static final Map<String, String> EVIDENCE = Map.of("source", "fixture:shared-river", "status", "known");

  @Test
  void verifiesTheSharedEdgeAndFreezesItsEvidenceInTheIssuedMarch(@TempDir Path world) throws Exception {
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), GM);
      var river = new RiverReference("fixture:raster", JsonCodec.sha256("fixture:raster"),
          List.of(new RiverReference.Line(6, List.of(new Vec2(95, 40), new Vec2(103, 40)))));
      var reference = new WorldGeography.Reference("worldsim-geo-v1", "fixture", root.ref().label(), root.stateHash(),
          200, 100, "fixture:calibration", List.of(new Vec2(-1, -1), new Vec2(1, -1), new Vec2(1, 1), new Vec2(-1, 1)),
          List.of(50.0, 180_000 / Math.PI, 0.0, 50.0, 0.0, -180_000 / Math.PI), 1,
          new WorldGeography.Check(3, 1, 1, 1), List.of(), List.of(), null, river);
      Files.writeString(world.resolve("geo-reference.json"), JsonCodec.canonicalJson(reference));
      var app = new WorldApplicationService(store);
      save(app, 1, List.of(
          Map.of("op", "setSimulationTime", "time", "1936-01-01T00:00:00"),
          Map.of("op", "putOrbatDocument", "document", Map.of("format", "orbat-native-v1", "documentId", "actual",
              "role", "actual", "root", Map.of("nodeId", "root", "name", "Unit",
                  "marchSpeed", Map.of("kmPerHour", 4, "evidence", EVIDENCE)))),
          Map.of("op", "putUnit", "unit", Map.of("id", "a", "name", "A", "provinceId", "west", "evidence", EVIDENCE,
              "actualRef", Map.of("documentId", "actual", "nodeId", "root"))),
          Map.of("op", "putUnit", "unit", Map.of("id", "b", "name", "B", "provinceId", "east", "evidence", EVIDENCE))));

      var edge = app.movementEstimate("main-1/orbat/unit/a", null, route(1)).route().legs().getFirst().edge();
      assertEquals("candidate", edge.river().status());
      assertEquals("intersects", edge.river().relation());
      assertEquals(river.sourceHash(), edge.river().sourceHash());
      assertEquals("rail-main", edge.railways().getFirst().railwayId());
      assertEquals("unknown", edge.railways().getFirst().status());
      var context = app.battleContext(route(1).getLast(), List.of("main-1/orbat/unit/a"), List.of("main-1/orbat/unit/b"));
      assertEquals(edge, context.attackers().getFirst().approach());
      assertEquals("unknown", context.defenders().getFirst().supply().status());
      assertEquals("BATTLE_INVALID", assertThrows(TimelineException.class, () ->
          app.battleContext(route(1).getLast(), List.of("main-1/orbat/unit/a"), List.of("main-1/orbat/unit/a"))).code());

      save(app, 2, List.of(Map.of("op", "setRiverCrossing", "fromAddress", route(1).getFirst(),
          "toAddress", route(1).getLast(), "crossesRiver", true, "evidence", EVIDENCE),
          Map.of("op", "rebuildAdjacencies")));
      var confirmed = app.movementEstimate("main-2/orbat/unit/a", null, route(2)).route().legs().getFirst().edge();
      assertEquals("confirmed-crossing", confirmed.river().status());
      assertEquals(EVIDENCE.get("source"), confirmed.river().source());
      save(app, 3, List.of(Map.of("op", "startMarch", "address", "main-2/orbat/unit/a", "route", route(2))));
      assertEquals(confirmed, app.savedMap("main-3").state().formations().marches().get("a").legs().getFirst().edge());
    }
    try (var store = FileTimelineStore.open(world)) {
      assertEquals(4, new TimelineService(store).verifyAll());
      assertEquals("confirmed-crossing", new WorldApplicationService(store).savedMap("main-3").state()
          .formations().marches().get("a").legs().getFirst().edge().river().status());
    }
  }

  private static List<String> route(long tick) {
    return List.of("main-" + tick + "/map/province/west", "main-" + tick + "/map/province/east");
  }

  private static void save(WorldApplicationService app, long tick, List<Map<String, Object>> commands) {
    String base = "main-" + (tick - 1);
    String hash = app.savedMap(base).stateHash();
    String draft = (String) app.open(GM, base, hash, null).get("draftId");
    app.apply(GM, draft, 0, "apply", commands, false);
    app.save(GM, draft, 1, "save-" + tick, tick, hash);
  }
}
