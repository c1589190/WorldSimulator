package io.mosire.worldsim.app;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CivicState;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorldCivic {
  private WorldCivic() {}

  record TreatyView(String address, CivicState.Treaty treaty, String status) {}
  record CensusSummary(String node, String stateHash, String date, String basis, String scope,
                       int provinces, int coveredProvinces, int missingProvinces,
                       int estimatedProvinces, Long recordedPeople) {}

  static Object population(WorldApplicationService.SavedMap saved, String dateText, String basis, NodeAddress scope) {
    String date;
    try { date = LocalDate.parse(dateText).toString(); }
    catch (DateTimeParseException ex) { throw new IllegalArgumentException("人口查询日期须采用 YYYY-MM-DD", ex); }
    NodeAddress.requireSegment(basis);
    var state = saved.state();
    var path = scope == null ? java.util.List.<String>of() : scope.path();
    if (scope != null && !scope.node().equals(saved.node())) throw new IllegalArgumentException("人口范围须与查询节点一致");
    java.util.function.Predicate<io.mosire.worldsim.map.Province> included;
    if (path.isEmpty()) {
      included = province -> true;
    } else if (path.size() == 2 && path.getFirst().equals("country")) {
      var id = WorldQueries.country(state, scope).id();
      included = province -> {
        var territory = state.countries().territories().get(province.id());
        return territory != null && territory.controller() != null && id.equals(territory.controller().countryId());
      };
    } else if (path.size() == 3 && path.getFirst().equals("map") && path.get(1).equals("state")) {
      var area = state.map().states().get(path.getLast());
      if (area == null) throw new TimelineException("ENTITY_NOT_FOUND", "州不存在: " + scope);
      var members = java.util.Set.copyOf(area.provinceIds());
      included = province -> members.contains(province.id());
    } else if (path.size() == 3 && path.getFirst().equals("map") && path.get(1).equals("province")) {
      var id = path.getLast();
      if (!state.map().provinces().containsKey(id)) throw new TimelineException("ENTITY_NOT_FOUND", "省不存在: " + scope);
      return state.civics().census().values().stream().filter(r -> r.provinceId().equals(id) && r.date().equals(date) && r.basis().equals(basis))
          .findFirst().<Object>map(record -> Map.of("address", scope.canonical(), "stateHash", saved.stateHash(), "record", record))
          .orElseGet(() -> Map.of("address", scope.canonical(), "stateHash", saved.stateHash(), "status", "unknown"));
    } else {
      throw new IllegalArgumentException("人口范围支持州、省、国家或全图");
    }
    int provinces = 0;
    int known = 0;
    int estimated = 0;
    long people = 0;
    for (var province : state.map().provinces().values()) {
      if (!province.kind().equals("land") || !included.test(province)) continue;
      provinces++;
      var record = state.civics().census().get(province.id() + "/" + date + "/" + basis);
      if (record == null) continue;
      known++;
      people += record.people();
      if (!record.evidence().status().equals("known")) estimated++;
    }
    return new CensusSummary(saved.node().label(), saved.stateHash(), date, basis, scope == null ? "all" : scope.canonical(),
        provinces, known, provinces - known, estimated, known == 0 ? null : people);
  }

  static Map<String, Object> profile(WorldApplicationService.SavedMap saved, NodeAddress address, String time) {
    StaticWorldState state = saved.state();
    var country = WorldQueries.country(state, address);
    var result = new LinkedHashMap<>(WorldQueries.lookup(state, address, saved.stateHash()));
    var politics = state.civics().politics().get(country.id());
    if (politics != null) result.put("politics", politics);
    result.put("treaties", state.civics().treaties().values().stream()
        .filter(t -> t.first().equals(country.id()) || t.second().equals(country.id()))
        .sorted(Comparator.comparing(CivicState.Treaty::id))
        .map(t -> new TreatyView(WorldQueries.address(address.node(), "diplomacy", "treaty", t.id()), t, t.statusAt(time))).toList());
    if (time != null) result.put("simulationTime", time);
    return result;
  }
}
