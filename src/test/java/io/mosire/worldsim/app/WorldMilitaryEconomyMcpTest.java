package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.app.WorldMcpProcessTest.Client;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.country.RailEconomy;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.orbat.CommandDepot;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.world.FileTimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldMilitaryEconomyMcpTest {
  private static final String CARGO = "radio";
  private static final Evidence PROOF = new Evidence("fixture:military-economy", "known", 1.0, null);

  @Test
  void settlesStocksAndSharedRailCapacityThroughMcpAndReplaysAcrossPeriods(@TempDir Path temp) throws Exception {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    Map<String, Object> finalSave;
    JsonNode finalNodes;
    try (var client = new Client(temp)) {
      client.initialize();
      var names = client.call("tools/list", Map.of()).path("tools").valueStream().map(t -> t.path("name").asText()).toList();
      assertTrue(names.containsAll(List.of("orbat_inventory", "orbat_deduct_equipment", "orbat_transfer_equipment",
          "orbat_reinforce_personnel", "world_rail_capacity")));
      var setup = open(client, "main-0", Map.of("eventTimeline", true));
      apply(client, setup, 0, "setup", openingCommands());
      assertEquals("main-1", client.tool("edit_save", setup.save(1, "save-setup")).path("node").asText());

      var opening = inventory(client, "main-1/orbat/unit/alpha");
      assertEquals("partial", opening.path("coverage").asText());
      assertEquals(5, opening.path("holderCount").asInt());
      assertEquals(16, stockCount(opening));
      assertEquals(1, opening.path("equipment").get(0).path("unknownHolders").asInt());
      assertEquals(1, client.tool("world_search", Map.of("node", "main-1", "kind", "depot", "query", "alpha")).path("total").asInt());

      var first = open(client, "main-1", Map.of());
      apply(client, first, 0, "advance", List.of(advance("00:15:00")));
      var spread = Map.<String, Object>of("unitAddress", "main-1/orbat/unit/alpha", "itemId", CARGO, "count", 9, "evidence", PROOF);
      var preview = first.arguments(1, "preview-spread", spread);
      preview.put("dryRun", true);
      var spreadResult = client.tool("orbat_deduct_equipment", preview);
      assertEquals(1, spreadResult.path("draftVersion").asInt());
      var deductions = spreadResult.path("steps").get(0).path("events").valueStream()
          .filter(event -> event.path("type").asText().equals("DeductEquipment")).findFirst().orElseThrow()
          .path("change").path("adjustments");
      assertEquals(List.of(2, 4, 3), deductions.valueStream().map(change -> change.path("before").asInt() - change.path("after").asInt()).toList());
      client.tool("orbat_deduct_equipment", first.arguments(1, "spread", spread));

      var precise = new LinkedHashMap<>(spread);
      precise.put("count", 1);
      precise.put("formationAddress", "main-1/orbat/doc/actual-alpha/node/b");
      client.tool("orbat_deduct_equipment", first.arguments(2, "precise", precise));
      var unknown = new LinkedHashMap<>(precise);
      unknown.put("formationAddress", "main-1/orbat/doc/actual-alpha/node/unknown");
      rejects(client, "orbat_deduct_equipment", first.arguments(3, "unknown", unknown), "STOCK_UNKNOWN");

      var transfer = delivery("main-1", "alpha", 4, "west", "east");
      var transferPreview = first.arguments(3, "preview-issue", transfer);
      transferPreview.put("dryRun", true);
      assertEquals(3, client.tool("orbat_transfer_equipment", transferPreview).path("draftVersion").asInt());
      assertEquals(20, stockCount(inventory(client, "main-1/orbat/depot/alpha")));
      assertEquals(4, client.tool("orbat_transfer_equipment", first.arguments(3, "issue", transfer)).path("draftVersion").asInt());
      assertEquals(4, client.tool("orbat_transfer_equipment", first.arguments(3, "issue", transfer)).path("draftVersion").asInt());
      rejects(client, "orbat_transfer_equipment", first.arguments(4, "country-exhausted",
          delivery("main-1", "alpha", 1, "west", "east")), "RAIL_CAPACITY_EXHAUSTED");

      var reinforcement = Map.<String, Object>of("countryAddress", "main-1/country/A",
          "unitAddress", "main-1/orbat/unit/alpha", "count", 5, "evidence", PROOF);
      var opposite = delivery("main-1", "beta", 2, "east", "west");
      rejects(client, "edit_apply", first.arguments(4, "shared-segment-exhausted", Map.of("commands", List.of(
          command("reinforcePersonnel", reinforcement), command("transferEquipment", opposite)))), "RAIL_CAPACITY_EXHAUSTED");
      client.tool("orbat_reinforce_personnel", first.arguments(4, "replace-personnel", reinforcement));
      assertEquals("main-2", client.tool("edit_save", first.save(5, "save-first-period")).path("node").asText());
      assertEquals(10, stockCount(inventory(client, "main-2/orbat/unit/alpha")));
      assertEquals(25, inventory(client, "main-2/orbat/doc/actual-alpha/node/root").path("recordedPersonnel").asInt());
      assertEquals(16, stockCount(inventory(client, "main-2/orbat/depot/alpha")));
      assertEquals(45, client.tool("world_lookup", Map.of("address", "main-2/country/A")).path("manpower").path("available").asInt());
      var capacity = capacity(client, "main-2", "B", "east", "west");
      assertEquals(1, capacity.path("piecesByCapacity").asInt());
      var segment = capacity.path("capacity").path("route").get(0).path("budget");
      assertEquals(500, segment.path("limitKg").asInt());
      assertEquals(400, segment.path("usedKg").asInt());

      var fork = open(client, "main-2", Map.of("forkBranch", "alternate"));
      apply(client, fork, 0, "same-period-fork", List.of(advance("00:45:00")));
      client.tool("edit_save", fork.save(1, "save-fork"));
      assertEquals(1, capacity(client, "alternate-3", "B", "east", "west").path("piecesByCapacity").asInt());

      var later = open(client, "main-2", Map.of());
      apply(client, later, 0, "advance-across-periods", List.of(advance("01:00:00"),
          command("transferEquipment", delivery("main-2", "beta", 2, "east", "west")), advance("02:05:00")));
      finalSave = later.save(1, "save-later");
      finalNodes = client.tool("edit_save", finalSave).path("nodes");
      assertEquals(List.of("1936-01-01T01:00:00", "1936-01-01T02:00:00", "1936-01-01T02:05:00"),
          finalNodes.valueStream().map(node -> node.path("eventTime").asText()).toList());
      assertEquals("rail-period-opened", finalNodes.get(0).path("events").get(0).path("type").asText());
      assertEquals(3, capacity(client, "main-3", "B", "east", "west").path("piecesByCapacity").asInt());
      assertEquals(5, capacity(client, "main-5", "B", "east", "west").path("piecesByCapacity").asInt());
    }

    try (var restarted = new Client(temp)) {
      restarted.initialize();
      var retry = restarted.tool("edit_save", finalSave);
      assertTrue(retry.path("replayedReceipt").asBoolean());
      assertEquals(finalNodes, retry.path("nodes"));
      assertEquals(2, stockCount(inventory(restarted, "main-5/orbat/unit/beta")));
      assertEquals(10, stockCount(inventory(restarted, "main-5/orbat/unit/alpha")));
      assertEquals(18, stockCount(inventory(restarted, "main-5/orbat/depot/beta")));
      assertEquals(16, stockCount(inventory(restarted, "main-1/orbat/unit/alpha")));
      var history = restarted.tool("timeline_diff", Map.of("from", "main-1", "to", "main-5", "offset", 1, "limit", 1));
      assertEquals(4, history.path("total").asInt());
      assertEquals("main-3", history.path("nodes").get(0).path("node").asText());
      assertEquals(200, history.path("nodes").get(0).path("militaryEconomy").get(0).path("freight").path("country").path("afterKg").asInt());
      assertEquals(7, restarted.tool("timeline_verify", Map.of()).path("verifiedNodes").asInt());
    }
  }

  @Test
  void localIssueWorksBeforeRailSetupAndLegacyClockStillClosesTheBudget(@TempDir Path temp) throws Exception {
    try (var store = FileTimelineStore.create(temp.resolve("world"), SampleWorld.WORLD_ID)) {
      new TimelineService(store).createGenesis(SampleWorld.create(), "gm:test");
    }
    try (var client = new Client(temp)) {
      client.initialize();
      var setup = open(client, "main-0", Map.of());
      apply(client, setup, 0, "setup-legacy", openingCommands().stream()
          .filter(command -> !List.of("configureRailEconomy", "putRailway").contains(command.get("op"))).toList());
      var setupSave = setup.arguments(1, "save-legacy", Map.of("baseHash", setup.hash(), "tick", 1));
      client.tool("edit_save", setupSave);

      var issue = open(client, "main-1", Map.of());
      var across = delivery("main-1", "alpha", 2, "west", "east");
      rejects(client, "orbat_transfer_equipment", issue.arguments(0, "unconfigured", across), "RAIL_ECONOMY_UNCONFIGURED");
      var local = new LinkedHashMap<>(delivery("main-1", "alpha", 1, "west", "east"));
      local.remove("route");
      apply(client, issue, 0, "local-issue", List.of(deploy("west"), command("transferEquipment", local), deploy("east")));

      var rules = openingCommands().getLast();
      apply(client, issue, 1, "budget", List.of(rules));
      rejects(client, "orbat_transfer_equipment", issue.arguments(2, "unknown-track", across), "RAIL_CAPACITY_UNKNOWN");
      apply(client, issue, 2, "issue-after-recording-track", List.of(rail("rail-main", 12.0), command("transferEquipment", across)));
      var reducedQuota = new RailEconomy.Rules(60, Map.of("A", new RailEconomy.Kilograms(150L, PROOF)), Map.of());
      apply(client, issue, 3, "reduce-quota", List.of(Map.of("op", "configureRailEconomy", "rules", reducedQuota)));
      var issueSave = issue.arguments(4, "save-issue", Map.of("baseHash", issue.hash(), "tick", 2));
      client.tool("edit_save", issueSave);
      assertEquals(200, capacity(client, "main-2", "A", "west", "east").path("capacity").path("country").path("usedKg").asInt());

      var rollover = open(client, "main-2", Map.of());
      apply(client, rollover, 0, "rollover", List.of(advance("01:00:00")));
      client.tool("edit_save", rollover.arguments(1, "save-rollover", Map.of("baseHash", rollover.hash(), "tick", 3)));
      var newPeriod = capacity(client, "main-3", "A", "west", "east");
      assertEquals(0, newPeriod.path("capacity").path("country").path("usedKg").asInt());
      assertEquals(1, newPeriod.path("piecesByCapacity").asInt());
      assertEquals(19, stockCount(inventory(client, "main-3/orbat/unit/alpha")));
      assertEquals(17, stockCount(inventory(client, "main-3/orbat/depot/alpha")));
      assertEquals(4, client.tool("timeline_verify", Map.of()).path("verifiedNodes").asInt());
    }
  }

  private record Draft(String id, String hash) {
    LinkedHashMap<String, Object> arguments(int version, String request, Map<String, Object> fields) {
      var args = new LinkedHashMap<>(fields);
      args.put("draftId", id);
      args.put("expectedVersion", version);
      args.put("requestId", request);
      return args;
    }

    Map<String, Object> save(int version, String request) { return arguments(version, request, Map.of("baseHash", hash)); }
  }

  private static Draft open(Client client, String base, Map<String, Object> options) throws Exception {
    String hash = client.tool("world_overview", Map.of("node", base)).path("stateHash").asText();
    var input = new LinkedHashMap<>(options);
    input.put("base", base);
    input.put("expectedHash", hash);
    return new Draft(client.tool("edit_open", input).path("draftId").asText(), hash);
  }

  private static void apply(Client client, Draft draft, int version, String request, List<Map<String, Object>> commands) throws Exception {
    assertEquals(version + 1, client.tool("edit_apply", draft.arguments(version, request, Map.of("commands", commands))).path("draftVersion").asInt());
  }

  private static void rejects(Client client, String tool, Map<String, Object> arguments, String code) throws Exception {
    var result = client.call("tools/call", Map.of("name", tool, "arguments", arguments));
    assertTrue(result.path("isError").asBoolean(), result.toString());
    assertTrue(result.toString().contains(code), result.toString());
  }

  private static JsonNode inventory(Client client, String address) throws Exception {
    return client.tool("orbat_inventory", Map.of("address", address, "details", true));
  }

  private static int stockCount(JsonNode inventory) {
    return inventory.path("equipment").valueStream().filter(item -> item.path("itemId").asText().equals(CARGO))
        .findFirst().orElseThrow().path("recordedCount").asInt();
  }

  private static JsonNode capacity(Client client, String base, String country, String from, String to) throws Exception {
    return client.tool("world_rail_capacity", Map.of("countryAddress", base + "/country/" + country, "itemId", CARGO,
        "route", List.of(base + "/map/province/" + from, base + "/map/province/" + to)));
  }

  private static Map<String, Object> delivery(String base, String id, int count, String from, String to) {
    return Map.of("depotAddress", base + "/orbat/depot/" + id, "unitAddress", base + "/orbat/unit/" + id,
        "itemId", CARGO, "count", count, "evidence", PROOF, "route", List.of(base + "/map/province/" + from, base + "/map/province/" + to));
  }

  private static Map<String, Object> advance(String time) { return Map.of("op", "advanceTime", "to", "1936-01-01T" + time); }

  private static Map<String, Object> deploy(String province) {
    return Map.of("op", "deployUnit", "unitId", "alpha", "provinceId", province, "evidence", PROOF);
  }

  private static Map<String, Object> command(String op, Map<String, Object> fields) {
    var result = new LinkedHashMap<>(fields);
    result.put("op", op);
    return result;
  }

  private static List<Map<String, Object>> openingCommands() {
    var alpha = node("root", 20, 0, List.of(node("a", 0, 2, List.of()), node("b", 0, 5, List.of()),
        node("c", 0, 9, List.of()), node("unknown", 0, null, List.of())));
    var beta = node("root", 20, 0, List.of());
    var rules = new RailEconomy.Rules(60, Map.of("A", new RailEconomy.Kilograms(450L, PROOF), "B", new RailEconomy.Kilograms(900L, PROOF)),
        Map.of(CARGO, new RailEconomy.Kilograms(100L, PROOF)));
    return List.of(
        Map.of("op", "setSimulationTime", "time", "1936-01-01T00:00:00"),
        Map.of("op", "putCountry", "country", new CountryState.Country("A", "A", List.of(), null, "west", PROOF)),
        Map.of("op", "putCountry", "country", new CountryState.Country("B", "B", List.of(), null, "east", PROOF)),
        Map.of("op", "setTerritory", "provinceIds", List.of("west"), "field", "owner", "assignment", new CountryState.Assignment("A", PROOF)),
        Map.of("op", "setTerritory", "provinceIds", List.of("east"), "field", "owner", "assignment", new CountryState.Assignment("B", PROOF)),
        Map.of("op", "setTransitRight", "grantorAddress", "main-0/country/A", "guestAddress", "main-0/country/B", "evidence", PROOF),
        Map.of("op", "setTransitRight", "grantorAddress", "main-0/country/B", "guestAddress", "main-0/country/A", "evidence", PROOF),
        rail("rail-main", 12.0), rail("rail-overlap", 9.6),
        Map.of("op", "putOrbatDocument", "document", new OrbatDocument(OrbatDocument.FORMAT, "actual-alpha", "actual", null, alpha)),
        Map.of("op", "putOrbatDocument", "document", new OrbatDocument(OrbatDocument.FORMAT, "actual-beta", "actual", null, beta)),
        unit("alpha", "A", "east"), unit("beta", "B", "west"),
        Map.of("op", "putCommandDepot", "depot", new CommandDepot("alpha", "Alpha command", "A", "west", stock(20), PROOF)),
        Map.of("op", "putCommandDepot", "depot", new CommandDepot("beta", "Beta command", "B", "east", stock(20), PROOF)),
        Map.of("op", "setManpowerPool", "countryAddress", "main-0/country/A", "available", 50, "evidence", PROOF),
        Map.of("op", "configureRailEconomy", "rules", rules));
  }

  private static Map<String, Object> rail(String id, double tonnesPerDay) {
    return Map.of("op", "putRailway", "railway", new Railway(id, 1, List.of("west", "east"),
        new Railway.Operation("open", tonnesPerDay, PROOF)));
  }

  private static Map<String, Object> unit(String id, String country, String province) {
    return Map.of("op", "putUnit", "unit", new OrbatState.Unit(id, id, null, country, province, null,
        new OrbatState.Ref("actual-" + id, "root", null), PROOF, null));
  }

  private static OrbatDocument.Inventory stock(Integer count) {
    var quantity = count == null ? OrbatDocument.Quantity.UNKNOWN : new OrbatDocument.Quantity(count, "known", PROOF.source(), 1.0);
    return new OrbatDocument.Inventory("complete", List.of(new OrbatDocument.Equipment(CARGO, "Radio", quantity)), PROOF);
  }

  private static OrbatDocument.Node node(String id, int personnel, Integer count, List<OrbatDocument.Node> children) {
    var strength = new OrbatDocument.Quantity(personnel, "known", PROOF.source(), 1.0);
    return new OrbatDocument.Node(id, id, null, null, strength, stock(count), children, Map.of());
  }
}
