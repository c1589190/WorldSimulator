package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.cli.WorldSimCli;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldMcpProcessTest {
  @Test
  void stdioLoadsWorldEnforcesProcessLockAndPersistsReceipt(@TempDir Path temp) throws Exception {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    String draft;
    String baseHash;
    try (var client = new Client(temp)) {
      client.initialize();
      JsonNode list = client.call("tools/list", Map.of());
      assertTrue(list.path("tools").size() >= 10);
      baseHash = client.tool("world_overview", Map.of()).path("stateHash").asText();
      draft = client.tool("edit_open", Map.of("base", "main-0", "expectedHash", baseHash)).path("draftId").asText();
      Map<String, Object> cmd = Map.of("op", "patchProvince", "id", "east", "name", "Via MCP");
      assertEquals(0, client.tool("edit_apply", Map.of("draftId", draft, "expectedVersion", 0,
          "requestId", "preview", "commands", List.of(cmd), "dryRun", true)).path("draftVersion").asInt());
      assertEquals(1, client.tool("edit_apply", Map.of("draftId", draft, "expectedVersion", 0,
          "requestId", "apply", "commands", List.of(cmd))).path("draftVersion").asInt());
      assertEquals("main-1", client.tool("edit_save", Map.of("draftId", draft, "expectedVersion", 1,
          "requestId", "save", "tick", 1, "baseHash", baseHash)).path("node").asText());
      assertEquals("Via MCP", client.tool("world_lookup", Map.of("address", "main-1/map/province/east"))
          .path("displayName").asText());
      Process competing = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"),
          WorldSimCli.class.getName()).directory(temp.toFile()).start();
      try {
        assertTrue(competing.waitFor(20, TimeUnit.SECONDS));
        assertNotEquals(0, competing.exitValue());
        assertTrue(new String(competing.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("WORLD_IN_USE"));
      } finally { competing.destroyForcibly(); }
    }
    try (var restarted = new Client(temp)) {
      restarted.initialize();
      var retry = restarted.tool("edit_save", Map.of("draftId", draft, "expectedVersion", 1,
          "requestId", "save", "tick", 1, "baseHash", baseHash));
      assertTrue(retry.path("replayedReceipt").asBoolean());
      assertEquals("main-1", retry.path("node").asText());
    }
  }

  private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }

  static final class Client implements AutoCloseable {
    private final Process process;
    private final OutputStreamWriter writer;
    private final BufferedReader reader;
    private int id;

    Client(Path cwd, String... args) throws Exception {
      var command = new java.util.ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"), WorldSimCli.class.getName()));
      command.addAll(List.of(args));
      process = new ProcessBuilder(command).directory(cwd.toFile()).start();
      writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
      reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }
    void initialize() throws Exception {
      call("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
          "clientInfo", Map.of("name", "worldsim-test", "version", "1")));
      send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
    }
    JsonNode call(String method, Map<String, Object> args) throws Exception {
      int expected = ++id;
      send(Map.of("jsonrpc", "2.0", "id", expected, "method", method, "params", args));
      String line = CompletableFuture.supplyAsync(() -> {
        try { return reader.readLine(); } catch (Exception e) { throw new RuntimeException(e); }
      }).get(30, TimeUnit.SECONDS);
      assertNotNull(line, "server exited before response");
      JsonNode response = JsonCodec.mapper().readTree(line);
      assertEquals(expected, response.path("id").asInt(), line);
      assertFalse(response.has("error"), line);
      return response.path("result");
    }
    JsonNode tool(String name, Map<String, Object> arguments) throws Exception {
      JsonNode result = call("tools/call", Map.of("name", name, "arguments", arguments));
      assertFalse(result.path("isError").asBoolean(), result.toString());
      return JsonCodec.mapper().readTree(result.path("content").get(0).path("text").asText());
    }
    void send(Map<String, Object> message) throws Exception {
      writer.write(JsonCodec.canonicalJson(message)); writer.write('\n'); writer.flush();
    }
    @Override public void close() throws Exception {
      try {
        writer.close();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "server did not stop on stdin EOF");
        assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
      } finally { process.destroyForcibly(); }
    }
  }
}
