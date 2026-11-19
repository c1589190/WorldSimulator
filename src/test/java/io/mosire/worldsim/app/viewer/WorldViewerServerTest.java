package io.mosire.worldsim.app.viewer;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.app.WorldApplicationService;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.PolygonPart;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Ring;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldViewerServerTest {
  @Test
  void usesOriginalPolygonsAndIsolatesDraftsAndSavedNodes(@TempDir Path temp) throws Exception {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      var genesis = new TimelineService(store).createGenesis(worldWithHole(), "gm:test");
      var app = new WorldApplicationService(store);
      try (var viewer = WorldViewerServer.start(app, 0);
           var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
        String base = "http://127.0.0.1:" + viewer.port();
        String query = "?node=main-0&hash=" + genesis.stateHash();
        assertEquals(200, get(http, base + "/").statusCode());
        assertEquals(200, get(http, base + "/viewer.js").statusCode());
        assertEquals(200, get(http, base + "/military.js").statusCode());
        assertEquals(404, get(http, base + "/world/world.json").statusCode());
        var post = HttpRequest.newBuilder(URI.create(base + "/api/map"))
            .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build();
        assertEquals(405, http.send(post, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(genesis.stateHash(), json(http, base + "/api/map").path("stateHash").asText());
        assertEquals(409, get(http, base + "/api/overview?node=main-0&hash=old&layer=terrain").statusCode());
        assertEquals(404, get(http, base + "/api/map?node=main-99").statusCode());

        var overview = png(http, base + "/api/overview" + query + "&layer=terrain");
        assertEquals(2048, overview.getWidth());
        assertEquals(1024, overview.getHeight());
        assertNotEquals(overview.getRGB(205, 205), overview.getRGB(512, 512));
        assertEquals(512, png(http, base + "/api/tile" + query + "&layer=region&level=2&x=0&y=0").getWidth());
        assertEquals(400, get(http, base + "/api/tile" + query + "&layer=region&level=2&x=16&y=0").statusCode());

        assertTrue(json(http, base + "/api/pick" + query + "&x=20&y=20").isNull());
        assertEquals("province:west", json(http, base + "/api/pick" + query + "&x=50&y=50").path("stableId").asText());
        assertEquals("province:east", json(http, base + "/api/pick" + query + "&x=100&y=50").path("stableId").asText());
        assertEquals(400, get(http, base + "/api/pick" + query + "&layer=missing&x=50&y=50").statusCode());
        assertEquals(400, get(http, base + "/api/pick" + query + "&x=NaN&y=50").statusCode());
        for (String layer : List.of("state", "region")) {
          String kindId = layer.equals("state") ? SampleWorld.STATE : SampleWorld.REGION;
          String picking = base + "/api/pick" + query + "&layer=" + layer;
          var west = json(http, picking + "&x=50&y=50");
          assertEquals(west, json(http, picking + "&x=150&y=50"));
          assertEquals(layer + ":" + kindId, west.path("stableId").asText());
          assertEquals("main-0/map/" + layer + "/" + kindId, west.path("address").asText());
          assertEquals(1, west.path("shape").path("parts").size());
          assertEquals(1, west.path("shape").path("parts").get(0).path("holes").size());
          assertEquals(west, json(http, base + "/api/entity" + query + "&address=" + west.path("address").asText()));
          assertTrue(json(http, picking + "&x=20&y=20").isNull());
          var aggregate = png(http, base + "/api/overview" + query + "&layer=" + layer);
          assertEquals(aggregate.getRGB(512, 512), aggregate.getRGB(1024, 512), "州或战略区内的省界已合并");
          assertNotEquals(aggregate.getRGB(512, 512), aggregate.getRGB(205, 205), "合并后保留洞");
        }
        var logistics = json(http, base + "/api/logistics" + query);
        assertEquals("depot-west", logistics.path("hubs").get(0).path("id").asText());
        assertEquals(2, logistics.path("railways").get(0).path("points").size());

        String draft = (String) app.open("gm:test", "main-0", genesis.stateHash(), null).get("draftId");
        app.apply("gm:test", draft, 0, "viewer-apply",
            List.of(Map.of("op", "patchProvince", "id", "west", "name", "New West")), false);
        String oldProvince = base + "/api/province" + query + "&address=main-0/map/province/west";
        assertEquals("West", json(http, oldProvince).path("displayName").asText());
        assertEquals(1, json(http, base + "/api/nodes").path("total").asInt());
        app.save("gm:test", draft, 1, "viewer-save", 1, genesis.stateHash());
        assertEquals(2, json(http, base + "/api/nodes").path("total").asInt());
        var next = json(http, base + "/api/map");
        assertEquals("main-1", next.path("node").asText());
        String newQuery = "?node=main-1&hash=" + next.path("stateHash").asText();
        assertEquals("New West", json(http, base + "/api/province" + newQuery
            + "&address=main-1/map/province/west").path("displayName").asText());
        assertEquals("West", json(http, oldProvince).path("displayName").asText());
        assertEquals(400, get(http, base + "/api/province" + newQuery
            + "&address=main-0/map/province/west").statusCode());
      }
    }
  }

  private static StaticWorldState worldWithHole() {
    StaticWorldState state = SampleWorld.create();
    Province west = state.map().provinces().get(SampleWorld.WEST);
    Ring hole = new Ring(List.of(new Vec2(10, 10), new Vec2(30, 10), new Vec2(30, 30), new Vec2(10, 30)));
    ProvinceShape shape = ProvinceShape.of(new PolygonPart(SampleWorld.ringA(), List.of(hole)));
    Province province = new Province(west.id(), west.name(), west.kind(), west.terrain(), shape,
        west.displayPoint(), west.stateId(), west.regionId(), west.tags());
    return new WorldChangeSet(List.of(new WorldChange.PutProvince(province))).apply(state);
  }

  private static HttpResponse<byte[]> get(HttpClient http, String url) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static JsonNode json(HttpClient http, String url) throws Exception {
    var response = get(http, url);
    assertEquals(200, response.statusCode(), url);
    return JsonCodec.mapper().readTree(response.body());
  }

  private static java.awt.image.BufferedImage png(HttpClient http, String url) throws Exception {
    var response = get(http, url);
    assertEquals(200, response.statusCode(), url);
    assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
    return ImageIO.read(new ByteArrayInputStream(response.body()));
  }
}
