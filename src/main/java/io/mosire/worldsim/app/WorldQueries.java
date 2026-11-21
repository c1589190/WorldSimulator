package io.mosire.worldsim.app;

import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.orbat.Battle;
import io.mosire.worldsim.orbat.CommandDepot;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.Evidence;

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
  record MarchMarker(String unitAddress, String name, String provinceAddress, String status,
                     String startedAt, String arrivalTime, String cancelledAt, double speedKmPerHour, Evidence evidence,
                     List<String> route, List<March.Leg> legs, List<Vec2> points, Vec2 point, int reachedLegs, double fraction,
                     March.Blockage blockage) {}
  record BattleParticipant(String address, String name) {}
  record BattleMarker(String kind, String stableId, String address, String name, String provinceAddress,
                      Vec2 point, String provinceName) {}
  private record QuantityDifference(int ideal, int actual, int delta, boolean estimated) {}
  private record EquipmentDifference(String itemId, String name, QuantityDifference quantity) {}

  static Map<String, Object> lookup(StaticWorldState state, NodeAddress address, String hash) {
    List<String> path = address.requireEntityPath().path();
    NodeRef node = address.node();
    if (path.size() == 3 && path.getFirst().equals("orbat") && path.get(1).equals("battle")) {
      var battle = state.formations().battles().get(path.getLast());
      if (battle == null) throw missing(address);
      return battle(state, node, hash, battle);
    }
    if (path.getFirst().equals("country") && path.size() == 2) {
      var country = country(state, address);
      var result = result(address, hash, "country", "country:" + country.id(), country.name(), country);
      if (country.capitalProvinceId() != null) {
        result.put("capitalAddress", address(node, "map", "province", country.capitalProvinceId()));
      }
      var territories = state.countries().territories().values();
      result.put("ownedProvinces", territories.stream()
          .filter(t -> t.owner() != null && t.owner().countryId().equals(country.id())).count());
      result.put("controlledProvinces", territories.stream()
          .filter(t -> t.controller() != null && t.controller().countryId().equals(country.id())).count());
      var grants = state.countries().transitRights().get(country.id());
      if (grants != null) result.put("transitRightsGranted", grants);
      var manpower = state.countries().manpower().get(country.id());
      if (manpower != null) result.put("manpower", manpower);
      return result;
    }
    if (path.size() == 3 && path.getFirst().equals("orbat") && path.get(1).equals("depot")) {
      var depot = depot(state, address);
      var result = result(address, hash, "depot", "depot:" + depot.id(), depot.name(), depot);
      result.put("provinceAddress", address(node, "map", "province", depot.provinceId()));
      result.put("countryAddress", address(node, "country", depot.countryId()));
      return result;
    }
    if (path.size() == 3 && path.getFirst().equals("orbat") && path.get(1).equals("unit")) {
      var unit = unit(state, address);
      var result = result(address, hash, "unit", "unit:" + unit.id(), unit.name(), unit);
      if (unit.provinceId() != null) {
        var province = state.map().provinces().get(unit.provinceId());
        result.put("provinceAddress", address(node, "map", "province", unit.provinceId()));
        result.put("provinceName", province.name());
        result.put("point", unitPoint(state, unit, province.displayPoint()));
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

  static OrbatState.Unit unit(StaticWorldState state, NodeAddress address) {
    var path = address.requireEntityPath().path();
    if (path.size() != 3 || !path.getFirst().equals("orbat") || !path.get(1).equals("unit")) throw invalid(address);
    var unit = state.formations().units().get(path.get(2));
    if (unit == null) throw missing(address);
    return unit;
  }

  static CountryState.Country country(StaticWorldState state, NodeAddress address) {
    var path = address.requireEntityPath().path();
    if (path.size() != 2 || !path.getFirst().equals("country")) throw invalid(address);
    var country = state.countries().countries().get(path.getLast());
    if (country == null) throw missing(address);
    return country;
  }

  static CommandDepot depot(StaticWorldState state, NodeAddress address) {
    var path = address.requireEntityPath().path();
    if (path.size() != 3 || !path.subList(0, 2).equals(List.of("orbat", "depot"))) throw invalid(address);
    var depot = state.formations().depots().get(path.getLast());
    if (depot == null) throw missing(address);
    return depot;
  }

  static OrbatDocument.Node formationsNode(OrbatState orbat, OrbatState.Ref reference) {
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
              address(node, "map", "province", unit.provinceId()), unitPoint(state, unit, province.displayPoint()),
              province.name(), type, echelon, unit.countryId(), country == null ? null : country.color());
        })
        .toList();
  }

  static List<MarchMarker> marches(StaticWorldState state, NodeRef node) {
    return state.formations().marches().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
      var unit = state.formations().units().get(entry.getKey());
      var march = entry.getValue();
      var progress = march.progress(state.simulationTime());
      var ids = new ArrayList<String>();
      ids.add(march.legs().getFirst().from());
      march.legs().forEach(leg -> ids.add(leg.to()));
      var route = ids.stream().map(id -> address(node, "map", "province", id)).toList();
      var provinces = state.map().provinces();
      List<Vec2> points = ids.stream().allMatch(provinces::containsKey)
          ? ids.stream().map(id -> provinces.get(id).displayPoint()).toList() : List.of();
      return new MarchMarker(address(node, "orbat", "unit", unit.id()), unit.name(),
          address(node, "map", "province", unit.provinceId()), progress.status(), march.startedAt(), march.arrivalTime(),
          march.cancelledAt(), march.speedKmPerHour(), march.evidence(), route, march.legs(), points,
          unitPoint(state, unit, provinces.get(unit.provinceId()).displayPoint()), progress.reachedLegs(), progress.fraction(),
          march.blockage());
    }).toList();
  }

  static BattleMarker battleMarker(StaticWorldState state, NodeRef node, Battle battle) {
    var place = state.map().provinces().get(battle.provinceId());
    if (place == null) return null;
    return new BattleMarker("battle", "battle:" + battle.id(), address(node, "orbat", "battle", battle.id()),
        place.name() + " · " + battle.id(), address(node, "map", "province", place.id()),
        place.displayPoint(), place.name());
  }

  static Map<String, Object> battle(StaticWorldState state, NodeRef node, String hash, Battle battle) {
    var place = state.map().provinces().get(battle.provinceId());
    String name = place == null ? battle.provinceId() : place.name();
    var result = result(new NodeAddress(node, List.of("orbat", "battle", battle.id())), hash,
        "battle", "battle:" + battle.id(), name + " · " + battle.id(), battle);
    if (place != null) {
      result.put("provinceAddress", address(node, "map", "province", place.id()));
      result.put("point", place.displayPoint());
    }
    result.put("attackers", participants(state.formations(), node, battle.attackers()));
    result.put("defenders", participants(state.formations(), node, battle.defenders()));
    if (battle.control() != null) {
      var country = state.countries().countries().get(battle.control().countryId());
      if (country != null) result.put("controllerName", country.name());
    }
    return result;
  }

  private static List<BattleParticipant> participants(OrbatState orbat, NodeRef node, List<String> ids) {
    return ids.stream().map(id -> {
      var unit = orbat.units().get(id);
      return new BattleParticipant(address(node, "orbat", "unit", id), unit == null ? id : unit.name());
    }).toList();
  }

  private static Vec2 unitPoint(StaticWorldState state, OrbatState.Unit unit, Vec2 provincePoint) {
    var march = state.formations().marches().get(unit.id());
    if (march == null) return provincePoint;
    var progress = march.progress(state.simulationTime());
    if (!progress.moving()) return provincePoint;
    Vec2 next = state.map().provinces().get(march.legs().get(progress.reachedLegs()).to()).displayPoint();
    return new Vec2(provincePoint.x() + (next.x() - provincePoint.x()) * progress.fraction(),
        provincePoint.y() + (next.y() - provincePoint.y()) * progress.fraction());
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

  static String formationAddress(NodeRef node, OrbatState.Ref reference) {
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
