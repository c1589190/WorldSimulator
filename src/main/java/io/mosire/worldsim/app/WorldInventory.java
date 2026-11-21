package io.mosire.worldsim.app;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.orbat.FormationStock;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Read projection over the same direct inventories that the stock operations change. */
final class WorldInventory {
  private WorldInventory() {}

  record Holder(String address, String name, OrbatDocument.Quantity personnel, OrbatDocument.Inventory equipment) {}

  record ItemTotal(String itemId, String name, long recordedCount, int countedHolders, int unknownHolders,
                   int estimatedHolders) {}

  private static final class Total {
    String name;
    long recorded;
    int counted;
    int unknown;
    int estimated;

    void accept(List<OrbatDocument.Equipment> entries) {
      var item = entries.getFirst();
      if (name == null) name = item.name();
      var quantity = item.quantity();
      if (entries.size() != 1 || quantity.value() == null) {
        unknown++;
      } else {
        recorded += quantity.value();
        counted++;
        if (quantity.status().equals("estimated")) estimated++;
      }
    }

    ItemTotal result(String itemId) { return new ItemTotal(itemId, name, recorded, counted, unknown, estimated); }
  }

  static Map<String, Object> read(WorldApplicationService.SavedMap saved, NodeAddress address, boolean details) {
    var result = new LinkedHashMap<String, Object>(Map.of("address", address.canonical(), "stateHash", saved.stateHash()));
    var path = address.requireEntityPath().path();
    var state = saved.state();
    List<Holder> holders;
    if (path.size() == 3 && path.subList(0, 2).equals(List.of("orbat", "unit"))) {
      result.put("scope", "subtree");
      var unit = WorldQueries.unit(state, address);
      var scope = FormationStock.actual(state.formations(), unit);
      if (scope == null) {
        result.put("status", "missing-actual");
        return Map.copyOf(result);
      }
      holders = FormationStock.nodes(scope.root()).stream().sorted(Comparator.comparing(OrbatDocument.Node::nodeId))
          .map(node -> holder(address, scope.document().documentId(), node)).toList();
    } else if (path.size() == 3 && path.subList(0, 2).equals(List.of("orbat", "depot"))) {
      result.put("scope", "depot");
      var depot = WorldQueries.depot(state, address);
      result.put("countryAddress", WorldQueries.address(saved.node(), "country", depot.countryId()));
      result.put("provinceAddress", WorldQueries.address(saved.node(), "map", "province", depot.provinceId()));
      result.put("evidence", depot.evidence());
      holders = List.of(new Holder(address.canonical(), depot.name(), null, depot.equipment()));
    } else {
      var formation = WorldQueries.formation(state, address);
      if (!formation.document().role().equals("actual") || formation.selected() == null) {
        throw new TimelineException("STOCK_FORMATION_INVALID", "盘点需要实际编制节点、单位地址或机关库存地址");
      }
      result.put("scope", "direct");
      holders = List.of(holder(address, formation.document().documentId(), formation.selected()));
    }
    summarize(result, holders);
    if (details || !result.get("scope").equals("subtree")) result.put("holders", holders);
    return Map.copyOf(result);
  }

  private static Holder holder(NodeAddress address, String documentId, OrbatDocument.Node node) {
    return new Holder(WorldQueries.address(address.node(), "orbat", "doc", documentId, "node", node.nodeId()),
        node.name(), node.personnel(), node.equipment());
  }

  private static void summarize(Map<String, Object> result, List<Holder> holders) {
    var totals = new TreeMap<String, Total>();
    long personnel = 0;
    int unknownPersonnel = 0;
    int estimatedPersonnel = 0;
    long unkeyed = 0;
    int incomplete = 0;
    int unknownInventory = 0;
    for (var holder : holders) {
      var size = holder.personnel();
      if (size != null) {
        if (size.value() == null) unknownPersonnel++;
        else personnel += size.value();
        if (size.status().equals("estimated")) estimatedPersonnel++;
      }
      var inventory = holder.equipment();
      if (!inventory.coverage().equals("complete")) incomplete++;
      if (inventory.coverage().equals("unknown")) unknownInventory++;
      var items = inventory.items();
      var keyed = items.stream().filter(item -> item.itemId() != null)
          .collect(Collectors.groupingBy(OrbatDocument.Equipment::itemId));
      unkeyed += items.stream().filter(item -> item.itemId() == null).count();
      keyed.forEach((id, entries) -> totals.computeIfAbsent(id, ignored -> new Total()).accept(entries));
    }
    var equipment = new ArrayList<ItemTotal>();
    totals.forEach((id, total) -> equipment.add(total.result(id)));
    boolean complete = incomplete == 0 && unkeyed == 0 && equipment.stream().noneMatch(item -> item.unknownHolders() > 0);
    result.put("coverage", complete ? "complete" : unknownInventory == holders.size() ? "unknown" : "partial");
    result.put("holderCount", holders.size());
    result.put("incompleteInventories", incomplete);
    result.put("unkeyedEquipmentEntries", unkeyed);
    result.put("equipment", List.copyOf(equipment));
    if (!result.get("scope").equals("depot")) {
      result.put("recordedPersonnel", personnel);
      result.put("unknownPersonnelHolders", unknownPersonnel);
      result.put("estimatedPersonnelHolders", estimatedPersonnel);
    }
  }
}
