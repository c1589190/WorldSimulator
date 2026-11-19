package io.mosire.worldsim.app;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.app.WorldApplicationService.SavedMap;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/** Read-only land march estimates for one saved world state. */
final class WorldMovement {
  private WorldMovement() {}

  record Limit(String address, Evidence evidence) {}
  record Speed(String status, Double kmPerHour, List<Limit> limitingUnits, List<String> missingSpeeds) {}
  record Leg(String from, String to, Double distanceKm, Double durationMinutes, March.Conditions conditions) {}
  record Route(String status, Double distanceKm, Double durationMinutes,
               WorldGeography.DistanceReference distanceReference, List<Leg> legs) {}
  record Estimate(String node, String stateHash, String address, String role, String formationAddress,
                  Speed speed, Route route) {}

  static Estimate estimate(SavedMap saved, NodeAddress address, String role, List<String> route, WorldGeography geography) {
    var unit = WorldQueries.unit(saved.state(), address);
    var reference = switch (role) {
      case "actual" -> unit.actualRef();
      case "ideal" -> unit.idealRef();
      default -> throw new IllegalArgumentException("role 须为 actual 或 ideal");
    };
    Speed speed = reference == null
        ? new Speed("unknown", null, List.of(), List.of())
        : speed(saved.node(), reference, WorldQueries.formationsNode(saved.state().formations(), reference));
    String formation = reference == null ? null : WorldQueries.formationAddress(saved.node(), reference);
    Route estimatedRoute = route == null ? null : route(saved, unit, route, speed.kmPerHour(), geography);
    return new Estimate(saved.node().label(), saved.stateHash(), address.canonical(), role, formation, speed, estimatedRoute);
  }

  static March issue(SavedMap saved, NodeAddress address, List<String> route, WorldGeography geography, String time) {
    var estimate = estimate(saved, address, "actual", route, geography);
    var plan = estimate.route();
    for (var leg : plan.legs()) {
      if (leg.conditions().passage().blocked()) {
        throw new TimelineException("MARCH_TRANSIT_BLOCKED", "无权进入 " + leg.to() + "，管理方 "
            + leg.conditions().passage().authorityCountryId());
      }
    }
    var speed = estimate.speed().kmPerHour();
    if (speed == null) throw new TimelineException("MARCH_SPEED_UNKNOWN", "实编行军速度未知: " + estimate.speed().missingSpeeds());
    if (speed == 0) throw new TimelineException("MARCH_IMMOBILE", "单位无法行军: " + address);
    if (plan.distanceKm() == null) throw new TimelineException("MARCH_DISTANCE_UNKNOWN", "路线含未配准的路段");
    if (plan.durationMinutes() == null) throw new TimelineException("MOVEMENT_DURATION_RANGE", "部分路段的生效速度无法计算");
    var legs = plan.legs().stream().map(leg -> new March.Leg(
        NodeAddress.parse(leg.from()).path().getLast(), NodeAddress.parse(leg.to()).path().getLast(),
        leg.distanceKm(), Math.max(1, (long) Math.ceil(leg.durationMinutes() * 60)), leg.conditions())).toList();
    var source = plan.distanceReference();
    var evidence = new Evidence(source.method() + " " + source.referenceHash() + " " + source.source(),
        "estimated", null, time.substring(0, 10));
    return new March(time, speed, evidence, legs, null);
  }

  private static Speed speed(NodeRef node, OrbatState.Ref reference, OrbatDocument.Node root) {
    var pending = new ArrayDeque<OrbatDocument.Node>();
    var limiting = new ArrayList<Limit>();
    var missing = new ArrayList<String>();
    double minimum = Double.POSITIVE_INFINITY;
    boolean estimated = false;
    pending.add(root);
    while (!pending.isEmpty()) {
      var next = pending.removeFirst();
      pending.addAll(next.children());
      var speed = next.marchSpeed();
      if (speed == null && !marches(next)) continue;
      String address = WorldQueries.formationAddress(node,
          new OrbatState.Ref(reference.documentId(), next.nodeId(), reference.contentHash()));
      if (speed == null) {
        missing.add(address);
        continue;
      }
      estimated |= !speed.evidence().status().equals("known");
      if (speed.kmPerHour() < minimum) {
        minimum = speed.kmPerHour();
        limiting.clear();
      }
      if (speed.kmPerHour() == minimum) limiting.add(new Limit(address, speed.evidence()));
    }
    if (minimum != 0 && (!missing.isEmpty() || limiting.isEmpty())) {
      return new Speed("unknown", null, List.of(), List.copyOf(missing));
    }
    return new Speed(estimated ? "estimated" : "known", minimum, List.copyOf(limiting), List.copyOf(missing));
  }

  private static boolean marches(OrbatDocument.Node node) {
    Integer personnel = node.personnel().value();
    return node.children().isEmpty() || (personnel != null && personnel > 0)
        || node.equipment().items().stream().anyMatch(item -> item.quantity().value() == null || item.quantity().value() > 0);
  }

  private static Route route(SavedMap saved, OrbatState.Unit unit, List<String> route, Double speed, WorldGeography geography) {
    if (route.size() < 2) throw invalidRoute("路线至少包含起点与终点");
    if (unit.provinceId() == null) throw invalidRoute("单位尚未部署");
    var provinces = route.stream().map(text -> landProvince(saved, text)).toList();
    if (!unit.provinceId().equals(provinces.getFirst().id())) throw invalidRoute("路线须从单位当前部署省开始");
    var ids = provinces.stream().map(Province::id).collect(Collectors.toSet());
    var edges = new HashSet<List<String>>();
    for (var edge : saved.map().adjacencies()) {
      if (edge.permitsLandMarch() && ids.contains(edge.a()) && ids.contains(edge.b())) {
        edges.add(List.of(edge.a(), edge.b()));
      }
    }
    var legs = new ArrayList<Leg>(route.size() - 1);
    var rules = new LandMarchRules(saved.state(), unit.countryId());
    double total = 0;
    double elapsed = 0;
    boolean complete = true;
    boolean timed = true;
    boolean blocked = false;
    for (int index = 1; index < provinces.size(); index++) {
      Province from = provinces.get(index - 1);
      Province to = provinces.get(index);
      String fromAddress = WorldQueries.address(saved.node(), "map", "province", from.id());
      String toAddress = WorldQueries.address(saved.node(), "map", "province", to.id());
      var pair = from.id().compareTo(to.id()) < 0 ? List.of(from.id(), to.id()) : List.of(to.id(), from.id());
      if (!edges.contains(pair)) throw invalidRoute("缺少普通陆地邻接: " + fromAddress + " → " + toAddress);
      Double distance = geography == null ? null : geography.distanceKm(from.displayPoint(), to.displayPoint());
      if (distance == null) complete = false;
      else total += distance;
      var conditions = rules.conditions(to, speed);
      boolean denied = conditions.passage().blocked();
      blocked |= denied;
      Double duration = denied ? null : minutes(distance, conditions.effectiveSpeedKmPerHour());
      if (duration == null) timed = false;
      else elapsed += duration;
      legs.add(new Leg(fromAddress, toAddress, distance, duration, conditions));
    }
    Double distance = complete ? total : null;
    Double duration = timed ? elapsed : null;
    if (duration != null && !Double.isFinite(duration)) throw new TimelineException("MOVEMENT_DURATION_RANGE", "路线耗时超出数值范围");
    String status = duration == null ? "unknown" : "estimated";
    if (speed != null && speed == 0) status = "immobile";
    if (blocked) status = "blocked";
    return new Route(status, distance, duration, geography == null ? null : geography.distanceReference(), List.copyOf(legs));
  }

  private static Province landProvince(SavedMap saved, String text) {
    NodeAddress address = NodeAddress.parse(text);
    if (!address.node().equals(saved.node())) throw invalidRoute("路线地址须与单位位于同一节点");
    if (MapAddressResolver.resolve(saved.map(), address).value() instanceof Province province && province.kind().equals("land")) {
      return province;
    }
    throw invalidRoute("路线只接受陆地省份地址: " + address);
  }

  private static Double minutes(Double distance, Double speed) {
    if (distance == null || speed == null || speed == 0) return null;
    double result = distance / speed * 60;
    if (!Double.isFinite(result)) throw new TimelineException("MOVEMENT_DURATION_RANGE", "行军耗时超出数值范围");
    return result;
  }

  private static TimelineException invalidRoute(String message) {
    return new TimelineException("MOVEMENT_ROUTE_INVALID", message);
  }
}
