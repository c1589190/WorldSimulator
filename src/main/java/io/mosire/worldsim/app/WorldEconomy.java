package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.country.RailEconomy;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.orbat.CommandDepot;
import io.mosire.worldsim.orbat.FormationStock;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans GM inventory and supply intents against the current draft. */
final class WorldEconomy {
  private WorldEconomy() {}

  static final Set<String> OPERATIONS = Set.of("deductEquipment", "transferEquipment", "reinforcePersonnel",
      "putCommandDepot", "setManpowerPool", "configureRailEconomy");
  private static final com.fasterxml.jackson.databind.ObjectMapper INPUT = JsonCodec.mapper().copy()
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
      .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

  sealed interface Command extends WorldCommand permits PutDepot, ConfigureRail, SetManpower, Deduct, Transfer, Reinforce {}

  static Command parse(String operation, Map<String, Object> command) {
    var fields = new LinkedHashMap<>(command);
    fields.remove("op");
    return switch (operation) {
      case "putCommandDepot" -> INPUT.convertValue(fields, PutDepot.class);
      case "configureRailEconomy" -> INPUT.convertValue(fields, ConfigureRail.class);
      case "setManpowerPool" -> INPUT.convertValue(fields, SetManpower.class);
      case "deductEquipment" -> INPUT.convertValue(fields, Deduct.class);
      case "transferEquipment" -> INPUT.convertValue(fields, Transfer.class);
      case "reinforcePersonnel" -> INPUT.convertValue(fields, Reinforce.class);
      default -> throw new IllegalArgumentException("未知军事经济命令: " + operation);
    };
  }

  record PutDepot(CommandDepot depot) implements Command {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      return new WorldChange.PutCommandDepot(depot);
    }
  }

  record ConfigureRail(RailEconomy.Rules rules) implements Command {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      return new WorldChange.ConfigureRailEconomy(context.requireTime(state), rules);
    }
  }

  record SetManpower(String countryAddress, Integer available, Evidence evidence) implements Command {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      var country = WorldQueries.country(state, requireAddress(context, countryAddress));
      return new WorldChange.SetManpowerPool(country.id(), new CountryState.Manpower(available, evidence));
    }
  }

  record Deduct(String unitAddress, String formationAddress, String itemId, int count, Evidence evidence) implements Command {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      requireCount(count);
      NodeAddress.requireSegment(itemId);
      var target = target(state, context, unitAddress, formationAddress);
      List<FormationStock.Change> adjustments;
      if (formationAddress == null) {
        adjustments = FormationStock.spread(target.node(), itemId, count);
        if (adjustments.isEmpty()) throw insufficient("该实际编制子树的可扣装备不足: " + itemId);
      } else {
        var inventory = target.node().equipment();
        int before = requireStock(FormationStock.equipmentCount(inventory, itemId), "目标装备: " + itemId);
        if (before < count) throw insufficient("目标节点直属装备不足，可扣 " + before);
        var item = FormationStock.equipment(inventory, itemId);
        adjustments = List.of(new FormationStock.Change(target.node().nodeId(), itemId, item.name(), before, before - count));
      }
      return new WorldChange.DeductEquipment(target.unit().id(), target.documentId(), itemId, adjustments, evidence);
    }
  }

  record Transfer(String depotAddress, String unitAddress, String formationAddress, String itemId, int count,
                  List<String> route, Evidence evidence) implements Command {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      requireCount(count);
      NodeAddress.requireSegment(itemId);
      var depot = WorldQueries.depot(state, requireAddress(context, depotAddress));
      var target = target(state, context, unitAddress, formationAddress);
      var unit = target.unit();
      if (!depot.countryId().equals(unit.countryId()) || unit.provinceId() == null) {
        throw new TimelineException("DELIVERY_TARGET_INVALID", "需要本国已部署的部队");
      }
      var march = state.formations().marches().get(unit.id());
      if (march != null) {
        var progress = march.progress(context.requireTime(state));
        if (progress.moving() && progress.fraction() > 0) {
          throw new TimelineException("DELIVERY_TARGET_MOVING", "目标部队在两省之间行军，请在抵达时交付");
        }
      }
      int depotBefore = requireStock(FormationStock.equipmentCount(depot.equipment(), itemId), "机关装备: " + itemId);
      if (depotBefore < count) throw insufficient("机关库存不足，可出库 " + depotBefore);
      int unitBefore = requireStock(FormationStock.equipmentCount(target.node().equipment(), itemId), "收货节点装备: " + itemId);
      var item = FormationStock.equipment(depot.equipment(), itemId);
      var receipt = new FormationStock.Change(target.node().nodeId(), itemId, item.name(), unitBefore, add(unitBefore, count));
      RailEconomy.Charge freight = null;
      if (unit.provinceId().equals(depot.provinceId())) {
        if (route != null && !route.isEmpty()) throw new TimelineException("RAIL_ROUTE_INVALID", "同省交付请省略铁路路线");
      } else {
        var saved = new WorldApplicationService.SavedMap(context.node(), context.stateHash(), state);
        freight = RailAllocation.charge(saved, depot.countryId(), itemId, count,
            depot.provinceId(), unit.provinceId(), route, context.landLinks().apply(saved));
      }
      return new WorldChange.TransferEquipment(depot.id(), unit.id(), target.documentId(), receipt,
          depotBefore, depotBefore - count, freight, evidence);
    }
  }

  record Reinforce(String countryAddress, String unitAddress, String formationAddress, int count,
                   Evidence evidence) implements Command {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      requireCount(count);
      var country = WorldQueries.country(state, requireAddress(context, countryAddress));
      var target = target(state, context, unitAddress, formationAddress);
      if (!country.id().equals(target.unit().countryId())) {
        throw new TimelineException("PERSONNEL_COUNTRY_MISMATCH", "补员须使用该部队所属国家的人力池");
      }
      var pool = state.countries().manpower().get(country.id());
      int poolBefore = requireStock(pool == null ? null : pool.available(), "国家人力池");
      if (poolBefore < count) throw insufficient("国家人力池不足，可补员 " + poolBefore);
      int before = requireStock(target.node().personnel().value(), "目标节点实编人数");
      return new WorldChange.ReinforcePersonnel(country.id(), target.unit().id(), target.documentId(),
          new FormationStock.Change(target.node().nodeId(), null, null, before, add(before, count)),
          poolBefore, poolBefore - count, evidence);
    }
  }

  private record Target(OrbatState.Unit unit, String documentId, OrbatDocument.Node node) {}

  private static Target target(StaticWorldState state, WorldCommand.Context context, String unitAddress, String formationAddress) {
    var unit = WorldQueries.unit(state, requireAddress(context, unitAddress));
    var scope = FormationStock.actual(state.formations(), unit);
    if (scope == null) throw new TimelineException("STOCK_ACTUAL_MISSING", "单位缺少实际编制: " + unitAddress);
    var node = scope.root();
    if (formationAddress != null) {
      var path = requireAddress(context, formationAddress).path();
      if (path.size() != 5 || !path.subList(0, 4).equals(List.of("orbat", "doc", scope.document().documentId(), "node"))
          || (node = scope.find(path.getLast())) == null) {
        throw new TimelineException("STOCK_FORMATION_INVALID", "地址须指向目标单位实际编制子树中的节点: " + formationAddress);
      }
    }
    return new Target(unit, scope.document().documentId(), node);
  }

  private static NodeAddress requireAddress(WorldCommand.Context context, String text) {
    var address = NodeAddress.parse(text);
    context.requireBase(address);
    return address;
  }

  private static void requireCount(int count) {
    if (count <= 0) throw new IllegalArgumentException("count 须为正整数");
  }

  private static int requireStock(Integer before, String label) {
    if (before == null) throw new TimelineException("STOCK_UNKNOWN", label + "缺少可用数量，请先核对实有基数");
    return before;
  }

  private static TimelineException insufficient(String message) {
    return new TimelineException("STOCK_INSUFFICIENT", message);
  }

  private static int add(int base, int count) {
    if (base > Integer.MAX_VALUE - count) throw new TimelineException("STOCK_RANGE", "结算后的实有数量超出范围");
    return base + count;
  }
}
