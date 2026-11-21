package io.mosire.worldsim.app;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.app.WorldApplicationService.SavedMap;
import io.mosire.worldsim.map.LandEdgeConditions;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Read-only land march estimates for one saved world state. */
final class WorldMovement {
  private WorldMovement() {}

  record Limit(String address, Evidence evidence) {}
  record Speed(String status, Double kmPerHour, List<Limit> limitingUnits, List<String> missingSpeeds) {}
  record Leg(String from, String to, Double distanceKm, Double durationMinutes, March.Conditions conditions, LandEdgeConditions edge) {}
  record Route(String status, Double distanceKm, Double durationMinutes,
               WorldGeography.DistanceReference distanceReference, List<Leg> legs) {}
  record Estimate(String node, String stateHash, String address, String role, String formationAddress,
                  Speed speed, Route route) {}

  static Estimate estimate(SavedMap saved, NodeAddress address, String role, List<String> route, WorldGeography geography, LandLinks links) {
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
    Route estimatedRoute = route == null ? null : route(saved, unit, route, speed.kmPerHour(), geography, links);
    return new Estimate(saved.node().label(), saved.stateHash(), address.canonical(), role, formation, speed, estimatedRoute);
  }

  record Plan(List<String> route, Estimate estimate) {}
  private record RouteStep(String id, double minutes) {}

  static Plan plan(SavedMap saved, NodeAddress address, String destination, List<String> via,
                   WorldGeography geography, LandLinks links) {
    if (geography == null) throw new TimelineException("MARCH_DISTANCE_UNKNOWN", "世界尚未配准");
    var unit = WorldQueries.unit(saved.state(), address);
    if (unit.provinceId() == null) throw invalidRoute("单位尚未部署");
    var speed = estimate(saved, address, "actual", null, geography, links).speed();
    if (speed.kmPerHour() == null) throw new TimelineException("MARCH_SPEED_UNKNOWN", "实编行军速度未知: " + speed.missingSpeeds());
    if (speed.kmPerHour() == 0) throw new TimelineException("MARCH_IMMOBILE", "单位无法行军");
    if (via.size() > 20) throw invalidRoute("途经点最多为 20 个");
    var rules = new LandMarchRules(saved.state(), unit.countryId());
    var targets = new ArrayList<>(via);
    targets.add(destination);
    var route = new ArrayList<String>();
    String current = unit.provinceId();
    route.add(WorldQueries.address(saved.node(), "map", "province", current));
    for (String addressText : targets) {
      String target = landProvince(saved, addressText).id();
      for (String id : fastestLandSegment(saved, current, target, speed.kmPerHour(), geography, links, rules)) {
        route.add(WorldQueries.address(saved.node(), "map", "province", id));
      }
      current = target;
    }
    return new Plan(List.copyOf(route), estimate(saved, address, "actual", route, geography, links));
  }

  private static List<String> fastestLandSegment(SavedMap saved, String start, String target, double speed,
                                                 WorldGeography geography, LandLinks links, LandMarchRules rules) {
    var frontier = new java.util.PriorityQueue<RouteStep>(java.util.Comparator.comparingDouble(RouteStep::minutes)
        .thenComparing(RouteStep::id));
    var best = new java.util.HashMap<String, Double>();
    var predecessor = new java.util.HashMap<String, String>();
    best.put(start, 0.0);
    frontier.add(new RouteStep(start, 0.0));
    while (!frontier.isEmpty()) {
      var step = frontier.remove();
      if (step.minutes() > best.get(step.id())) continue;
      if (step.id().equals(target)) {
        var path = new ArrayDeque<String>();
        for (String id = target; !id.equals(start); id = predecessor.get(id)) path.addFirst(id);
        return List.copyOf(path);
      }
      var from = saved.map().provinces().get(step.id());
      for (String id : links.neighbors(step.id())) {
        var to = saved.map().provinces().get(id);
        if (to == null || !to.kind().equals("land") || links.edge(from, to) == null) continue;
        var conditions = rules.conditions(to, speed);
        if (conditions.passage().blocked()) continue;
        Double distance = geography.distanceKm(from.displayPoint(), to.displayPoint());
        if (distance == null) continue;
        Double minutes = minutes(distance, conditions.effectiveSpeedKmPerHour());
        if (minutes == null) continue;
        double candidate = step.minutes() + minutes;
        if (candidate >= best.getOrDefault(id, Double.POSITIVE_INFINITY)) continue;
        best.put(id, candidate);
        predecessor.put(id, step.id());
        frontier.add(new RouteStep(id, candidate));
      }
    }
    throw new TimelineException("MARCH_ROUTE_UNAVAILABLE", "没有找到已配准且可通行的陆路: " + start + " → " + target);
  }

  static March issue(SavedMap saved, NodeAddress address, List<String> route, WorldGeography geography, LandLinks links, String time) {
    var estimate = estimate(saved, address, "actual", route, geography, links);
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
        leg.distanceKm(), Math.max(1, (long) Math.ceil(leg.durationMinutes() * 60)), leg.conditions(), leg.edge())).toList();
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

  private static Route route(SavedMap saved, OrbatState.Unit unit, List<String> route, Double speed, WorldGeography geography,
      LandLinks links) {
    if (route.size() < 2) throw invalidRoute("路线至少包含起点与终点");
    if (unit.provinceId() == null) throw invalidRoute("单位尚未部署");
    var provinces = route.stream().map(text -> landProvince(saved, text)).toList();
    if (!unit.provinceId().equals(provinces.getFirst().id())) throw invalidRoute("路线须从单位当前部署省开始");
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
      var edge = links.edge(from, to);
      if (edge == null) throw invalidRoute("缺少普通陆地邻接: " + fromAddress + " → " + toAddress);
      Double distance = geography == null ? null : geography.distanceKm(from.displayPoint(), to.displayPoint());
      if (distance == null) complete = false;
      else total += distance;
      var conditions = rules.conditions(to, speed);
      boolean denied = conditions.passage().blocked();
      blocked |= denied;
      Double duration = denied ? null : minutes(distance, conditions.effectiveSpeedKmPerHour());
      if (duration == null) timed = false;
      else elapsed += duration;
      legs.add(new Leg(fromAddress, toAddress, distance, duration, conditions, edge));
    }
    Double distance = complete ? total : null;
    Double duration = timed ? elapsed : null;
    if (duration != null && !Double.isFinite(duration)) throw new TimelineException("MOVEMENT_DURATION_RANGE", "路线耗时超出数值范围");
    String status = duration == null ? "unknown" : "estimated";
    if (speed != null && speed == 0) status = "immobile";
    if (blocked) status = "blocked";
    return new Route(status, distance, duration, geography == null ? null : geography.distanceReference(), List.copyOf(legs));
  }

  static Province landProvince(SavedMap saved, String text) {
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
