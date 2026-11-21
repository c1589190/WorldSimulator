package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.map.LandEdgeConditions;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.orbat.Battle;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class WorldBattle {
  private WorldBattle() {}

  record Request(String id, String provinceAddress, List<String> attackers, List<String> defenders, String startedAt,
                 String outcome, String rationale, Evidence evidence, List<LossRequest> losses, String controllerAddress) {}
  record LossRequest(String unitAddress, String formationAddress, JsonNode personnel, List<EquipmentRequest> equipment) {}
  record EquipmentRequest(String itemId, String name, JsonNode count) {}
  private record Subtree(OrbatDocument document, Set<String> ids) {}

  record Participant(String address, String provinceAddress, String terrain, March.Supply supply, LandEdgeConditions approach) {}
  record Context(String node, String stateHash, String provinceAddress, String terrain,
                 List<Participant> attackers, List<Participant> defenders) {}

  static Context context(WorldApplicationService.SavedMap saved, WorldCommand.Context commandContext, String address,
      List<String> attackers, List<String> defenders) {
    var province = WorldMovement.landProvince(saved, address);
    var state = saved.state();
    var units = new HashMap<String, OrbatState.Unit>();
    var attackIds = side(state, commandContext, attackers, province.id(), true, units);
    var defendIds = side(state, commandContext, defenders, province.id(), false, units);
    var supply = new HashMap<String, LandMarchRules>();
    var links = commandContext.landLinks().apply(saved);
    Function<String, Participant> view = id -> {
      var unit = units.get(id);
      var home = saved.map().provinces().get(unit.provinceId());
      var rules = supply.computeIfAbsent(unit.countryId(), country -> new LandMarchRules(state, country));
      return new Participant(WorldQueries.address(saved.node(), "orbat", "unit", id),
          WorldQueries.address(saved.node(), "map", "province", home.id()), home.terrain(), rules.supply(home.id()),
          home.id().equals(province.id()) ? null : links.edge(home, province));
    };
    return new Context(saved.node().label(), saved.stateHash(), address, province.terrain(),
        attackIds.stream().map(view).toList(), defendIds.stream().map(view).toList());
  }

  static Battle resolve(StaticWorldState state, WorldCommand.Context context, Request request) {
    if (request == null) throw invalid("需要战斗裁决");
    NodeAddress.requireSegment(request.id());
    if (state.formations().battles().containsKey(request.id())) throw new TimelineException("BATTLE_ALREADY_RESOLVED", "战斗 ID 已结算");
    String endedAt = context.requireTime(state);
    NodeAddress location = NodeAddress.parse(request.provinceAddress());
    context.requireBase(location);
    if (!(MapAddressResolver.resolve(state.map(), location).value() instanceof Province province) || !province.kind().equals("land")) {
      throw invalid("交战地点须为陆地省份");
    }
    var units = new HashMap<String, OrbatState.Unit>();
    var attackers = side(state, context, request.attackers(), province.id(), true, units);
    var defenders = side(state, context, request.defenders(), province.id(), false, units);
    var scopes = subtrees(state.formations(), units);
    var losses = request.losses() == null ? List.<Battle.Loss>of()
        : request.losses().stream().map(loss -> loss(state, context, scopes, loss)).toList();

    Battle.Control change = null;
    if (request.controllerAddress() != null) {
      String country = countryId(state, context, request.controllerAddress());
      var old = state.countries().territories().get(province.id());
      String previous = old == null || old.controller() == null ? null : old.controller().countryId();
      change = new Battle.Control(previous, country);
    }
    return new Battle(request.id(), province.id(), context.node().label(), context.stateHash(),
        request.startedAt() == null ? endedAt : request.startedAt(), endedAt, attackers, defenders,
        request.outcome(), request.rationale(), request.evidence(), losses, change);
  }

  static String countryId(StaticWorldState state, WorldCommand.Context context, String text) {
    var address = NodeAddress.parse(text);
    context.requireBase(address);
    var path = address.path();
    if (path.size() != 2 || !path.getFirst().equals("country") || !state.countries().countries().containsKey(path.getLast())) {
      throw new IllegalArgumentException("国家地址不存在: " + text);
    }
    return path.getLast();
  }

  private static List<String> side(StaticWorldState state, WorldCommand.Context context, List<String> side,
                                   String province, boolean attacking, Map<String, OrbatState.Unit> units) {
    if (side == null || side.isEmpty()) throw invalid("攻守双方均需提供单位地址");
    var neighbors = new HashSet<String>();
    if (attacking) {
      for (var edge : state.map().adjacencies()) {
        if (!edge.permitsLandMarch()) continue;
        if (edge.a().equals(province)) neighbors.add(edge.b());
        if (edge.b().equals(province)) neighbors.add(edge.a());
      }
    }
    var ids = new ArrayList<String>();
    for (String text : side) {
      var unit = unit(state, context, text);
      if (unit.provinceId() == null || !unit.provinceId().equals(province) && !neighbors.contains(unit.provinceId())) {
        throw invalid("单位部署与交战地点不相邻或不在交战省: " + text);
      }
      if (units.putIfAbsent(unit.id(), unit) != null) throw invalid("参与单位重复: " + text);
      ids.add(unit.id());
    }
    return List.copyOf(ids);
  }

  private static OrbatState.Unit unit(StaticWorldState state, WorldCommand.Context context, String text) {
    var address = NodeAddress.parse(text);
    context.requireBase(address);
    return WorldQueries.unit(state, address);
  }

  private static Map<String, Subtree> subtrees(OrbatState orbat, Map<String, OrbatState.Unit> participants) {
    var all = new HashSet<List<String>>();
    var scopes = new HashMap<String, Subtree>();
    for (var unit : participants.values()) {
      var ref = unit.actualRef();
      if (ref == null) continue;
      var document = orbat.findDocument(ref.documentId(), ref.contentHash());
      var root = document == null ? null : document.findNode(ref.nodeId());
      if (root == null || !document.role().equals("actual")) throw invalid("实际编制无法解析: " + unit.id());
      var ids = new HashSet<String>();
      var pending = new ArrayDeque<OrbatDocument.Node>();
      pending.add(root);
      while (!pending.isEmpty()) {
        var node = pending.removeFirst();
        if (!all.add(List.of(document.documentId(), node.nodeId()))) throw invalid("参战单位实际编制重叠");
        ids.add(node.nodeId());
        pending.addAll(node.children());
      }
      scopes.put(unit.id(), new Subtree(document, ids));
    }
    return scopes;
  }

  private static Battle.Loss loss(StaticWorldState state, WorldCommand.Context context, Map<String, Subtree> scopes,
                                  LossRequest request) {
    if (request == null) throw invalid("损耗条目不能为空");
    var unit = unit(state, context, request.unitAddress());
    var address = NodeAddress.parse(request.formationAddress());
    context.requireBase(address);
    var scope = scopes.get(unit.id());
    var path = address.path();
    if (scope == null || path.size() != 5 || !path.get(0).equals("orbat") || !path.get(1).equals("doc")
        || !path.get(2).equals(scope.document().documentId()) || !path.get(3).equals("node") || !scope.ids().contains(path.getLast())) {
      throw invalid("损耗地址须在参战单位的实际编制子树内: " + address);
    }
    var node = scope.document().findNode(path.getLast());
    Battle.Count personnel = request.personnel() == null || request.personnel().isNull()
        ? null : Battle.Count.of(node.personnel().value(), positive(request.personnel()));
    var equipment = request.equipment() == null ? List.<Battle.EquipmentLoss>of()
        : request.equipment().stream().map(item -> equipmentLoss(node.equipment().items(), item)).toList();
    return new Battle.Loss(unit.id(), scope.document().documentId(), node.nodeId(), personnel, equipment);
  }

  private static Battle.EquipmentLoss equipmentLoss(List<OrbatDocument.Equipment> items, EquipmentRequest request) {
    if (request == null) throw invalid("装备损耗条目不能为空");
    if ((request.itemId() == null) == (request.name() == null)) throw invalid("装备损耗须提供 itemId 或精确 name");
    int match = -1;
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      if (request.itemId() != null ? request.itemId().equals(item.itemId()) : request.name().equals(item.name())) {
        if (match >= 0) throw invalid("装备条目不唯一");
        match = i;
      }
    }
    if (match < 0) throw invalid("装备条目不存在");
    var item = items.get(match);
    return new Battle.EquipmentLoss(match, item.itemId(), item.name(), Battle.Count.of(item.quantity().value(), positive(request.count())));
  }

  private static int positive(JsonNode value) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
      throw invalid("损耗须为正整数");
    }
    return value.intValue();
  }

  private static TimelineException invalid(String message) { return new TimelineException("BATTLE_INVALID", message); }
}
