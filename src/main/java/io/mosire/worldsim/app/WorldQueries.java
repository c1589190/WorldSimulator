package io.mosire.worldsim.app;

import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.StaticWorldState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class WorldQueries {
  private WorldQueries() {}

  record Formation(OrbatDocument document, OrbatDocument.Node selected) {}
  record UnitMarker(String stableId, String address, String name, String provinceAddress, Vec2 point,
                    String provinceName, String type, String echelon,
                    String countryId, String countryColor) {}
  private record QuantityDifference(int ideal, int actual, int delta, boolean estimated) {}
  private record EquipmentDifference(String itemId, String name, QuantityDifference quantity) {}

  static Map<String, Object> lookup(StaticWorldState state, NodeAddress address, String hash) {
    List<String> path = address.requireEntityPath().path();
    NodeRef node = address.node();
    if (path.getFirst().equals("country") && path.size() == 2) {
      var country = state.countries().countries().get(path.get(1));
      if (country == null) throw missing(address);
      var result = result(address, hash, "country", "country:" + country.id(), country.name(), country);
      if (country.capitalProvinceId() != null) {
        result.put("capitalAddress", address(node, "map", "province", country.capitalProvinceId()));
      }
      var territories = state.countries().territories().values();
      result.put("ownedProvinces", territories.stream()
          .filter(t -> t.owner() != null && t.owner().countryId().equals(country.id())).count());
      result.put("controlledProvinces", territories.stream()
          .filter(t -> t.controller() != null && t.controller().countryId().equals(country.id())).count());
      return result;
    }
    if (path.size() == 3 && path.getFirst().equals("orbat") && path.get(1).equals("unit")) {
      var unit = state.formations().units().get(path.get(2));
      if (unit == null) throw missing(address);
      var result = result(address, hash, "unit", "unit:" + unit.id(), unit.name(), unit);
      if (unit.provinceId() != null) {
        var province = state.map().provinces().get(unit.provinceId());
        result.put("provinceAddress", address(node, "map", "province", unit.provinceId()));
        result.put("provinceName", province.name());
        result.put("point", province.displayPoint());
      }
      if (unit.countryId() != null) {
        var country = state.countries().countries().get(unit.countryId());
        result.put("countryAddress", address(node, "country", unit.countryId()));
        result.put("countryName", country.name());
        if (country.color() != null) result.put("countryColor", country.color());
      }
      if (unit.idealRef() != null) result.put("idealAddress", formationAddress(node, unit.idealRef()));
      if (unit.actualRef() != null) result.put("actualAddress", formationAddress(node, unit.actualRef()));
      if (unit.idealRef() != null && unit.actualRef() != null) {
        var comparisons = compare(formationsNode(state.formations(), unit.idealRef()),
            formationsNode(state.formations(), unit.actualRef()));
        if (!comparisons.isEmpty()) result.put("directStrengthDifference", comparisons);
      }
      return result;
    }
    Formation selected = formation(state, address);
    var document = selected.document();
    boolean isDocument = selected.selected() == null;
    String stable = "orbat:" + document.documentId() + (isDocument ? "" : "/" + selected.selected().nodeId());
    String name = isDocument ? document.root().name() : selected.selected().name();
    var result = result(address, hash, isDocument ? "orbatDocument" : "orbatNode", stable, name,
        isDocument ? document : selected.selected());
    result.put("role", document.role());
    result.put("documentHash", document.contentHash());
    return result;
  }

  private static OrbatDocument.Node formationsNode(OrbatState orbat, OrbatState.Ref reference) {
    return orbat.findDocument(reference.documentId(), reference.contentHash()).findNode(reference.nodeId());
  }

  private static Map<String, Object> compare(OrbatDocument.Node ideal, OrbatDocument.Node actual) {
    var result = new LinkedHashMap<String, Object>();
    var personnel = difference(ideal.personnel(), actual.personnel());
    if (personnel != null) result.put("personnel", personnel);
    var expectedItems = itemsById(ideal.equipment());
    var actualItems = itemsById(actual.equipment());
    var equipment = new ArrayList<EquipmentDifference>();
    for (String itemId : new java.util.TreeSet<>(actualItems.keySet())) {
      var group = actualItems.get(itemId);
      var expected = expectedItems.get(itemId);
      if (group.size() != 1 || expected == null || expected.size() != 1) continue;
      var item = group.getFirst();
      var count = difference(expected.getFirst().quantity(), item.quantity());
      if (count != null) equipment.add(new EquipmentDifference(itemId, item.name(), count));
    }
    if (!equipment.isEmpty()) result.put("equipment", List.copyOf(equipment));
    return result;
  }

  private static Map<String, List<OrbatDocument.Equipment>> itemsById(OrbatDocument.Inventory inventory) {
    return inventory.items().stream().filter(item -> item.itemId() != null)
        .collect(Collectors.groupingBy(OrbatDocument.Equipment::itemId));
  }

  private static QuantityDifference difference(OrbatDocument.Quantity ideal, OrbatDocument.Quantity actual) {
    if (ideal.value() == null || actual.value() == null) return null;
    boolean estimated = ideal.status().equals("estimated") || actual.status().equals("estimated");
    return new QuantityDifference(ideal.value(), actual.value(), actual.value() - ideal.value(), estimated);
  }

  static Formation formation(StaticWorldState state, NodeAddress address) {
    List<String> path = address.path();
    if (path.size() < 3 || !path.getFirst().equals("orbat") || !path.get(1).equals("doc")) {
      throw invalid(address);
    }
    int index = 3;
    String hash = null;
    if (path.size() >= 5 && path.get(3).equals("version")) {
      hash = path.get(4);
      index = 5;
    }
    if (path.size() != index && (path.size() != index + 2 || !path.get(index).equals("node"))) {
      throw invalid(address);
    }
    var document = state.formations().findDocument(path.get(2), hash);
    if (document == null) throw missing(address);
    var selected = path.size() == index ? null : document.findNode(path.get(index + 1));
    if (path.size() != index && selected == null) throw missing(address);
    return new Formation(document, selected);
  }

  /** Projects deployed unit instances, each of which can reference its own ORBAT subtree. */
  static List<UnitMarker> unitMarkers(StaticWorldState state, NodeRef node, String provinceId) {
    var formations = new HashMap<OrbatState.Ref, OrbatDocument.Node>();
    return state.formations().units().values().stream()
        .filter(unit -> unit.provinceId() != null && (provinceId == null || provinceId.equals(unit.provinceId())))
        .sorted(Comparator.comparing(OrbatState.Unit::id))
        .map(unit -> {
          var province = state.map().provinces().get(unit.provinceId());
          var country = unit.countryId() == null ? null : state.countries().countries().get(unit.countryId());
          var actual = unit.actualRef() == null ? null : formations.computeIfAbsent(unit.actualRef(),
              ref -> formationsNode(state.formations(), ref));
          var ideal = unit.idealRef() == null ? null : formations.computeIfAbsent(unit.idealRef(),
              ref -> formationsNode(state.formations(), ref));
          String type = label(unit.type(), actual == null ? null : actual.type(), ideal == null ? null : ideal.type());
          String echelon = label(actual == null ? null : actual.echelon(), ideal == null ? null : ideal.echelon());
          return new UnitMarker("unit:" + unit.id(), address(node, "orbat", "unit", unit.id()), unit.name(),
              address(node, "map", "province", unit.provinceId()), province.displayPoint(),
              province.name(), type, echelon, unit.countryId(), country == null ? null : country.color());
        })
        .toList();
  }

  private static String label(String... labels) {
    for (String label : labels) {
      if (label != null && !label.isBlank()) return label;
    }
    return null;
  }

  static String address(NodeRef node, String... path) {
    return new NodeAddress(node, List.of(path)).canonical();
  }

  private static String formationAddress(NodeRef node, OrbatState.Ref reference) {
    var path = new ArrayList<>(List.of("orbat", "doc", reference.documentId()));
    if (reference.contentHash() != null) {
      path.add("version");
      path.add(reference.contentHash());
    }
    path.add("node");
    path.add(reference.nodeId());
    return new NodeAddress(node, path).canonical();
  }

  private static Map<String, Object> result(NodeAddress address, String hash, String kind, String id,
                                           String name, Object entity) {
    return new LinkedHashMap<>(Map.of("address", address.canonical(), "nodeHash", hash,
        "kind", kind, "stableId", id, "displayName", name, "entity", entity));
  }

  private static AddressException invalid(NodeAddress address) {
    return new AddressException("ENTITY_ADDRESS_UNSUPPORTED", "实体地址路径不支持: " + address);
  }

  private static AddressException missing(NodeAddress address) {
    return new AddressException("ENTITY_NOT_FOUND", "实体不存在: " + address);
  }
}
