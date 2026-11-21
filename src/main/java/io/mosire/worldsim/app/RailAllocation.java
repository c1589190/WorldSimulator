package io.mosire.worldsim.app;

import io.mosire.worldsim.country.RailEconomy;
import io.mosire.worldsim.map.LandEdgeConditions;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.TimelineException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Resolves and prices a route using the currently effective map and rail budget. */
final class RailAllocation {
  private RailAllocation() {}

  record Budget(String status, Long limitKg, long usedKg, Long remainingKg, Evidence evidence) {
    static Budget available(String status, Long limit, long used, Evidence evidence) {
      return new Budget(status, limit, used, limit == null ? null : Math.max(limit - used, 0), evidence);
    }
  }

  record Leg(String fromAddress, String toAddress, RailEconomy.Segment segment, String railwayId, Budget budget) {}

  record Capacity(String countryAddress, RailEconomy.Period period, Budget country, List<Leg> route,
                  Long remainingKg) {}

  static Capacity capacity(WorldApplicationService.SavedMap saved, String countryId, List<String> route, LandLinks links) {
    var rail = saved.state().countries().railEconomy();
    var limit = rail == null ? null : rail.rules().countryQuotas().get(countryId);
    var country = Budget.available(rail == null ? "unconfigured" : limit == null ? "unknown" : "configured",
        limit == null ? null : limit.kilograms(), rail == null ? 0 : rail.usedByCountry(countryId),
        limit == null ? null : limit.evidence());
    var legs = route == null ? List.<Leg>of() : legs(saved, countryId, route, links, rail);
    Long remaining = country.remainingKg();
    for (var leg : legs) {
      var legRemaining = leg.budget().remainingKg();
      if (Long.valueOf(0).equals(remaining) || Long.valueOf(0).equals(legRemaining)) remaining = 0L;
      else if (remaining == null || legRemaining == null) remaining = null;
      else remaining = Math.min(remaining, legRemaining);
    }
    return new Capacity(WorldQueries.address(saved.node(), "country", countryId), rail == null ? null : rail.period(),
        country, legs, remaining);
  }

  static RailEconomy.Charge charge(WorldApplicationService.SavedMap saved, String countryId, String itemId, int count,
                                  String from, String to, List<String> route, LandLinks links) {
    if (route == null || route.size() < 2) throw new TimelineException("RAIL_ROUTE_INVALID", "跨省交付需要铁路省份地址序列");
    if (!WorldMovement.landProvince(saved, route.getFirst()).id().equals(from)
        || !WorldMovement.landProvince(saved, route.getLast()).id().equals(to)) {
      throw new TimelineException("RAIL_ROUTE_INVALID", "铁路路线首尾须为机关和部队的驻省");
    }
    var rail = saved.state().countries().railEconomy();
    if (rail == null) throw new TimelineException("RAIL_ECONOMY_UNCONFIGURED", "请先配置铁路经济期和国家额度");
    var weight = rail.rules().itemWeights().get(itemId);
    if (weight == null) throw new TimelineException("RAIL_WEIGHT_UNKNOWN", "该装备缺少单件运输重量: " + itemId);
    long load;
    try { load = Math.multiplyExact(weight.kilograms(), count); }
    catch (ArithmeticException e) { throw new TimelineException("RAIL_WEIGHT_RANGE", "交付重量超出范围", e); }

    var capacity = capacity(saved, countryId, route, links);
    var country = spend(capacity.country(), load, capacity.countryAddress());
    var segments = new ArrayList<RailEconomy.SegmentCharge>();
    for (var leg : capacity.route()) {
      var balance = spend(leg.budget(), load, leg.fromAddress() + " → " + leg.toAddress());
      segments.add(new RailEconomy.SegmentCharge(leg.segment(), leg.railwayId(), leg.budget().evidence(), balance));
    }
    return new RailEconomy.Charge(rail.periodIndex(), country, segments);
  }

  private static RailEconomy.Balance spend(Budget budget, long amount, String location) {
    if (budget.limitKg() == null) throw new TimelineException("RAIL_CAPACITY_UNKNOWN", "运力不可确认: " + location + " (" + budget.status() + ")");
    if (budget.remainingKg() < amount) {
      throw new TimelineException("RAIL_CAPACITY_EXHAUSTED", location + " 可用 " + budget.remainingKg() + " kg，需要 " + amount + " kg (" + budget.status() + ")");
    }
    return new RailEconomy.Balance(budget.limitKg(), budget.usedKg(), budget.usedKg() + amount);
  }

  private static List<Leg> legs(WorldApplicationService.SavedMap saved, String countryId, List<String> route,
                                LandLinks links, RailEconomy rail) {
    if (route.size() < 2) throw new TimelineException("RAIL_ROUTE_INVALID", "铁路路线至少需要两个省份地址");
    var ids = route.stream().map(address -> WorldMovement.landProvince(saved, address).id()).toList();
    if (new HashSet<>(ids).size() != ids.size()) throw new TimelineException("RAIL_ROUTE_INVALID", "铁路路线不能重复经过同一省");
    var result = new ArrayList<Leg>();
    for (int index = 1; index < ids.size(); index++) {
      String from = ids.get(index - 1);
      String to = ids.get(index);
      var segment = new RailEconomy.Segment(from, to);
      var tracks = links.railTracks(from, to);
      var best = tracks.stream().filter(track -> track.status().equals("open") && track.tonnesPerDay() != null)
          .max(Comparator.comparingDouble((LandEdgeConditions.Track track) -> track.tonnesPerDay())
              .thenComparing(LandEdgeConditions.Track::railwayId, Comparator.reverseOrder())).orElse(null);
      var countries = saved.state().countries();
      var fromPassage = countries.entry(countryId, from);
      var toPassage = countries.entry(countryId, to);
      long used = rail == null ? 0 : rail.usedOnSegment(segment);
      Budget budget;
      if (fromPassage.blocked() || toPassage.blocked()) {
        budget = Budget.available("blocked", 0L, used, fromPassage.blocked() ? fromPassage.evidence() : toPassage.evidence());
      } else if (fromPassage.status().equals("unknown") || toPassage.status().equals("unknown")) {
        budget = Budget.available("unknown-control", null, used, null);
      } else if (tracks.isEmpty()) {
        budget = Budget.available("no-track", 0L, used, null);
      } else if (tracks.stream().allMatch(track -> track.status().equals("closed"))) {
        budget = Budget.available("closed", 0L, used, tracks.getFirst().evidence());
      } else if (rail == null || best == null) {
        budget = Budget.available(rail == null ? "unconfigured" : "unknown", null, used, null);
      } else {
        budget = Budget.available("open", kilogramsPerPeriod(best.tonnesPerDay(), rail.rules().periodMinutes()), used, best.evidence());
      }
      result.add(new Leg(route.get(index - 1), route.get(index), segment, best == null ? null : best.railwayId(), budget));
    }
    return List.copyOf(result);
  }

  private static long kilogramsPerPeriod(double tonnesPerDay, int minutes) {
    try {
      return BigDecimal.valueOf(tonnesPerDay).multiply(BigDecimal.valueOf(1_000L * minutes))
          .divide(BigDecimal.valueOf(1_440), 0, RoundingMode.DOWN).longValueExact();
    } catch (ArithmeticException e) {
      throw new TimelineException("RAIL_CAPACITY_RANGE", "路段当期公斤数超出范围", e);
    }
  }
}
