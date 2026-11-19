package io.mosire.worldsim.app;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Ring;
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

class WorldApplicationServiceTest {
  @Test
  void previewsAtomicallySavesAndRetriesAfterRestart(@TempDir Path temp) throws Exception {
    Path world = temp.resolve("world");
    String draftId;
    String baseHash;
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID)) {
      var initial = SampleWorld.create();
      var root = new TimelineService(store).createGenesis(initial, "gm:test");
      baseHash = root.stateHash();
      Files.writeString(world.resolve("provenance.json"), JsonCodec.prettyJson(Map.of(
          "formatVersion", 1, "worldId", initial.id(), "node", root.ref().label(), "stateHash", root.stateHash(),
          "source", Map.of("name", "fixture"), "fieldEvidence", List.of(Map.of(
              "entityType", "province", "ids", List.of("east"), "field", "name", "source", "fixture", "status", "inferred")))));
      var app = new WorldApplicationService(store);
      assertTrue(app.lookup("main-0/map/province/east", false).containsKey("fieldEvidence"));
      var opened = app.open("gm:test", "main-0", baseHash, null);
      draftId = (String) opened.get("draftId");
      var valid = Map.<String, Object>of("op", "patchProvince", "id", "east", "name", "Updated");
      var invalid = Map.<String, Object>of("op", "patchProvince", "id", "missing", "name", "Bad");
      var preview = app.apply("gm:test", draftId, 0, "preview", List.of(valid), true);
      assertEquals(0L, preview.get("draftVersion"));
      assertThrows(TimelineException.class, () -> app.apply("gm:test", draftId, 0, "bad", List.of(valid, invalid), false));
      var applied = app.apply("gm:test", draftId, 0, "apply", List.of(valid), false);
      assertEquals(1L, applied.get("draftVersion"));
      assertEquals(applied, app.apply("gm:test", draftId, 0, "apply", List.of(valid), false));
      assertEquals("DRAFT_VERSION_CONFLICT", assertThrows(TimelineException.class,
          () -> app.apply("gm:test", draftId, 0, "stale", List.of(valid), false)).code());
      var saved = app.save("gm:test", draftId, 1, "save-1", 1, baseHash);
      assertEquals("main-1", saved.get("node"));
      var rootManifest = JsonCodec.mapper().readTree(Files.readString(world.resolve("world.json")));
      Path changes = world.resolve(rootManifest.path("heads").path("main").asText()).resolve("changes.json");
      assertTrue(Files.readString(changes).contains("patchProvince"));
      assertFalse(Files.readString(changes).contains("shape"), "renaming must not duplicate polygon geometry");
      assertEquals("Updated", new TimelineService(store).load(new NodeRef(new BranchId("main"), 1))
          .map().provinces().get("east").name());
      assertFalse(app.lookup("main-1/map/province/east", false).containsKey("fieldEvidence"));
      assertTrue(Files.isRegularFile(world.resolve("view/current.json")));
      String exported = (String) app.exportWorld().get("path");
      assertTrue(Files.isRegularFile(world.resolve(exported).resolve("provenance.json")));
      try (var copy = FileTimelineStore.open(world.resolve(exported))) {
        assertEquals(2, new TimelineService(copy).verifyAll());
      }
    }
    try (var store = FileTimelineStore.open(world)) {
      var app = new WorldApplicationService(store);
      var retry = app.save("gm:test", draftId, 1, "save-1", 1, baseHash);
      assertEquals(true, retry.get("replayedReceipt"));
      assertEquals("REQUEST_ID_CONFLICT", assertThrows(TimelineException.class,
          () -> app.save("gm:test", draftId, 1, "save-1", 2, baseHash)).code());
      assertEquals(2, new TimelineService(store).verifyAll());
    }
  }

  @Test
  void agentlibEnforcesRoleAndResourceAtCallBoundary(@TempDir Path temp) {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
      var tools = WorldTools.create(new WorldApplicationService(store));
      var authorizer = ToolCallAuthorizer.standard();
      var gm = permissions(RoleSet.of("GM"), ResourceScope.of("/" + SampleWorld.WORLD_ID));
      var reader = permissions(RoleSet.of("DM"), ResourceScope.of("/" + SampleWorld.WORLD_ID));
      var deniedResource = permissions(RoleSet.of("GM"), ResourceScope.none());
      assertTrue(authorizer.execute(tools, "world_overview", context(gm)).success());
      assertEquals("PERMISSION_DENIED", authorizer.execute(tools, "world_overview", context(reader)).code());
      assertEquals("RESOURCE_DENIED", authorizer.execute(tools, "world_overview", context(deniedResource)).code());
      var unexpected = new ToolContext(AccessToken.DEFAULT, gm, Map.of(), Map.of("evil", true), AgentIdentity.main(CommandMode.FULL));
      assertEquals("ARGUMENT_INVALID", authorizer.execute(tools, "world_overview", unexpected).code());
    }
  }

  @Test
  void geometryEditCanRebuildBorderAndPreserveRailway(@TempDir Path temp) {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      var initial = SampleWorld.create();
      var root = new TimelineService(store).createGenesis(initial, "gm:test");
      var app = new WorldApplicationService(store);
      var opened = app.open("gm:test", "main-0", root.stateHash(), null);
      String draft = (String) opened.get("draftId");
      var shape = ProvinceShape.of(new Ring(List.of(new Vec2(100, 20), new Vec2(200, 20),
          new Vec2(200, 100), new Vec2(100, 100))));
      Map<String, Object> patch = Map.of("op", "patchProvince", "id", "east", "shape", shape);
      assertEquals("TIMELINE_TOPOLOGY_INVALID", assertThrows(TimelineException.class,
          () -> app.apply("gm:test", draft, 0, "bad-border", List.of(patch), false)).code());
      app.apply("gm:test", draft, 0, "fixed-border", List.of(patch, Map.of("op", "rebuildAdjacencies")), false);
      app.save("gm:test", draft, 1, "save-border", 1, root.stateHash());
      var map = new TimelineService(store).load(new NodeRef(new BranchId("main"), 1)).map();
      assertEquals(80.0, map.adjacencies().get(0).borderLength());
      assertTrue(map.railways().containsKey("rail-main"));
    }
  }

  @Test
  void aPreviewIsNotReusedAfterTheDraftChanges(@TempDir Path temp) {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      var root = new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
      var app = new WorldApplicationService(store);
      String draft = (String) app.open("gm:test", "main-0", root.stateHash(), null).get("draftId");
      var east = Map.<String, Object>of("op", "patchProvince", "id", "east", "name", "East edited");
      var west = Map.<String, Object>of("op", "patchProvince", "id", "west", "name", "West edited");
      app.apply("gm:test", draft, 0, "preview", List.of(east), true);
      app.apply("gm:test", draft, 0, "west", List.of(west), false);
      app.apply("gm:test", draft, 1, "east", List.of(east), false);
      app.save("gm:test", draft, 2, "save", 1, root.stateHash());
      var map = new TimelineService(store).load(new NodeRef(new BranchId("main"), 1)).map();
      assertEquals("East edited", map.provinces().get("east").name());
      assertEquals("West edited", map.provinces().get("west").name());
    }
  }

  private static AgentPermissionSet permissions(RoleSet roles, ResourceScope scope) {
    return AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().destructiveAllowed(true)
        .roles(roles).resourceScopes(ResourceScopeMap.of("world", scope)).build();
  }
  private static ToolContext context(AgentPermissionSet permissions) {
    return ToolContext.of(AccessToken.DEFAULT, permissions, AgentIdentity.main(CommandMode.FULL));
  }
}
