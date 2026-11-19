package io.mosire.worldsim.app;

import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.state.StaticWorldState;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned movement parameters and domestic rail supply reachability for one army. */
final class LandMarchRules {
  static final String VERSION = "land-march-v1";
  private static final Map<String, Double> TERRAIN = Map.of(
      "plains", 1.0, "urban", .85, "forest", .75, "hills", .75, "desert", .8,
      "mountain", .55, "marsh", .55, "jungle", .6);
  private static final March.Supply UNKNOWN = new March.Supply("unknown", null, null);

  private final CountryState countries;
  private final String countryId;
  private final String source;
  private final String sourceAuthority;
  private final Map<String, March.Supply> covered;

  LandMarchRules(StaticWorldState state, String countryId) {
    this.countries = state.countries();
    this.countryId = countryId;
    var country = countryId == null ? null : countries.countries().get(countryId);
    var capital = country == null || country.capitalProvinceId() == null ? null : state.map().provinces().get(country.capitalProvinceId());
    source = capital != null && capital.kind().equals("land") ? capital.id() : null;
    sourceAuthority = source == null ? null : countries.authority(source);
    covered = sourceAuthority != null && sourceAuthority.equals(countryId) ? coverage(state.map()) : Map.of();
  }

  March.Supply supply(String provinceId) {
    if (provinceId == null || sourceAuthority == null || countries.authority(provinceId) == null) return UNKNOWN;
    return covered.getOrDefault(provinceId, new March.Supply("disconnected", source, null));
  }

  March.Conditions conditions(Province destination, Double baseSpeed) {
    Double terrain = TERRAIN.get(destination.terrain());
    double terrainFactor = terrain == null ? 1 : terrain;
    var supply = supply(destination.id());
    double supplyFactor = supply.status().equals("disconnected") ? .7 : 1;
    Double speed = baseSpeed == null ? null : baseSpeed * terrainFactor * supplyFactor;
    return new March.Conditions(VERSION, destination.terrain(), terrain != null,
        terrainFactor, supply, supplyFactor, countries.entry(countryId, destination.id()), speed);
  }

  private Map<String, March.Supply> coverage(WorldMap map) {
    var tracks = new HashSet<List<String>>();
    for (var railway : map.railways().values()) {
      var path = railway.provincePath();
      for (int i = 1; i < path.size(); i++) {
        String a = path.get(i - 1);
        String b = path.get(i);
        if (domestic(a) && domestic(b)) tracks.add(a.compareTo(b) < 0 ? List.of(a, b) : List.of(b, a));
      }
    }
    var graph = new HashMap<String, Set<String>>();
    for (var edge : map.adjacencies()) {
      if (edge.permitsLandMarch() && tracks.contains(List.of(edge.a(), edge.b()))) {
        graph.computeIfAbsent(edge.a(), ignored -> new HashSet<>()).add(edge.b());
        graph.computeIfAbsent(edge.b(), ignored -> new HashSet<>()).add(edge.a());
      }
    }
    var reached = new HashSet<String>();
    var pending = new ArrayDeque<String>();
    reached.add(source);
    pending.add(source);
    while (!pending.isEmpty()) {
      for (String next : graph.getOrDefault(pending.removeFirst(), Set.of())) {
        if (reached.add(next)) pending.add(next);
      }
    }
    var providers = new HashMap<String, March.Supply>();
    providers.put(source, new March.Supply("connected", source, null));
    map.supplyHubs().values().stream().filter(hub -> reached.contains(hub.provinceId()))
        .sorted(Comparator.comparing(SupplyHub::id)).forEach(hub ->
            providers.putIfAbsent(hub.provinceId(), new March.Supply("connected", source, hub.id())));
    var result = new HashMap<>(providers);
    for (var edge : map.adjacencies()) {
      if (!edge.permitsLandMarch()) continue;
      var a = providers.get(edge.a());
      var b = providers.get(edge.b());
      if (a != null && domestic(edge.b())) result.putIfAbsent(edge.b(), a);
      if (b != null && domestic(edge.a())) result.putIfAbsent(edge.a(), b);
    }
    return result;
  }

  private boolean domestic(String province) {
    return countryId.equals(countries.authority(province));
  }
}
