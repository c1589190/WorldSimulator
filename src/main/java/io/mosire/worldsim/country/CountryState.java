package io.mosire.worldsim.country;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.Evidence;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Country identities and province-level claims for a single timeline state. */
public record CountryState(Map<String, Country> countries, Map<String, Territory> territories,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Map<String, Evidence>> transitRights,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Manpower> manpower,
                           RailEconomy railEconomy) {
  public static final CountryState EMPTY = new CountryState(Map.of(), Map.of());

  public CountryState(Map<String, Country> countries, Map<String, Territory> territories) {
    this(countries, territories, Map.of());
  }

  public CountryState(Map<String, Country> countries, Map<String, Territory> territories,
                      Map<String, Map<String, Evidence>> transitRights) {
    this(countries, territories, transitRights, Map.of(), null);
  }

  public record Country(String id, String name, List<String> aliases, String color,
                        String capitalProvinceId, Evidence evidence) {
    public Country {
      NodeAddress.requireSegment(id);
      if (name == null || name.isBlank()) throw new IllegalArgumentException("国家名称不得为空");
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
      if (aliases.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("国家别名不得为空");
      if (color != null && !color.matches("#[0-9a-fA-F]{6}")) {
        throw new IllegalArgumentException("国家颜色须采用 #RRGGBB");
      }
      if (capitalProvinceId != null) NodeAddress.requireSegment(capitalProvinceId);
      Objects.requireNonNull(evidence, "国家来源");
    }
  }

  public record Assignment(String countryId, Evidence evidence) {
    public Assignment {
      NodeAddress.requireSegment(countryId);
      Objects.requireNonNull(evidence, "归属来源");
      if (evidence.status().equals("unknown") || evidence.status().equals("legacy-ambiguous")) {
        throw new IllegalArgumentException("明确归属须有支持该归属的证据");
      }
    }
  }

  public record Territory(Assignment owner, Assignment controller) {}

  public record Manpower(Integer available, Evidence evidence) {
    public Manpower {
      if (available == null || available < 0) throw new IllegalArgumentException("人力池须为非负整数");
      Evidence.requireRecorded(evidence);
    }
  }

  public record Passage(String status, String authorityCountryId, String basis, Evidence evidence) {
    public boolean blocked() { return status.equals("blocked"); }
  }

  public CountryState {
    countries = Map.copyOf(countries);
    territories = Map.copyOf(territories);
    var rightCopy = new TreeMap<String, Map<String, Evidence>>();
    if (transitRights != null) transitRights.forEach((country, guests) -> rightCopy.put(country, Map.copyOf(guests)));
    transitRights = Map.copyOf(rightCopy);
    manpower = manpower == null ? Map.of() : Map.copyOf(manpower);
    for (var entry : countries.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("国家键与 ID 不一致");
    }
  }

  public CountryState withCountry(Country country) {
    var next = new TreeMap<>(countries);
    next.put(country.id(), country);
    return new CountryState(next, territories, transitRights, manpower, railEconomy);
  }

  public CountryState withTerritory(List<String> provinces, String field, Assignment assignment) {
    var next = new TreeMap<>(territories);
    for (String province : provinces) {
      Territory old = next.getOrDefault(province, new Territory(null, null));
      Territory value = switch (field) {
        case "owner" -> new Territory(assignment, old.controller());
        case "controller" -> new Territory(old.owner(), assignment);
        default -> throw new IllegalArgumentException("未知归属字段: " + field);
      };
      if (value.owner() == null && value.controller() == null) next.remove(province);
      else next.put(province, value);
    }
    return new CountryState(countries, next, transitRights, manpower, railEconomy);
  }

  public CountryState withTransitRight(String grantor, String guest, Evidence evidence) {
    if (!countries.containsKey(grantor) || !countries.containsKey(guest) || grantor.equals(guest)) {
      throw new IllegalArgumentException("通行权需要不同的两个已登记国家");
    }
    if (evidence != null) new Assignment(grantor, evidence);
    var rights = new TreeMap<>(transitRights);
    var grants = new TreeMap<>(rights.getOrDefault(grantor, Map.of()));
    if (evidence == null) grants.remove(guest);
    else grants.put(guest, evidence);
    if (grants.isEmpty()) rights.remove(grantor);
    else rights.put(grantor, grants);
    return new CountryState(countries, territories, rights, manpower, railEconomy);
  }

  public CountryState withManpower(String countryId, Manpower pool) {
    if (!countries.containsKey(countryId) || pool == null) throw new IllegalArgumentException("人力池需要已登记国家及数量");
    var next = new TreeMap<>(manpower);
    next.put(countryId, pool);
    return new CountryState(countries, territories, transitRights, next, railEconomy);
  }

  public CountryState spendManpower(String countryId, int before, int after, Evidence evidence) {
    var pool = manpower.get(countryId);
    if (pool == null || pool.available() != before || after < 0 || after >= before) {
      throw new IllegalArgumentException("补员扣账与人力池基数不符");
    }
    return withManpower(countryId, new Manpower(after, Evidence.afterAdjustment(pool.evidence(), evidence)));
  }

  public CountryState withRailEconomy(RailEconomy next) {
    return next == railEconomy ? this : new CountryState(countries, territories, transitRights, manpower, next);
  }

  public CountryState advanceEconomy(String time) {
    return railEconomy == null ? this : withRailEconomy(railEconomy.atTime(time));
  }

  public String authority(String provinceId) {
    var territory = territories.get(provinceId);
    if (territory == null) return null;
    var authority = territory.controller() == null ? territory.owner() : territory.controller();
    return authority == null ? null : authority.countryId();
  }

  public Passage entry(String guest, String provinceId) {
    var territory = territories.get(provinceId);
    if (territory == null) return new Passage("unknown", null, null, null);
    var authority = territory.controller() == null ? territory.owner() : territory.controller();
    if (authority == null) return new Passage("unknown", null, null, null);
    String host = authority.countryId();
    String basis = territory.controller() == null ? "owner" : "controller";
    if (host.equals(guest)) return new Passage("domestic", host, basis, authority.evidence());
    var permission = guest == null ? null : transitRights.getOrDefault(host, Map.of()).get(guest);
    return permission == null
        ? new Passage("blocked", host, basis, authority.evidence())
        : new Passage("granted", host, basis, permission);
  }

  public void validate(WorldMap map) {
    if (!countries.keySet().containsAll(manpower.keySet()) || railEconomy != null
        && (!countries.keySet().containsAll(railEconomy.rules().countryQuotas().keySet())
            || !countries.keySet().containsAll(railEconomy.countryUse().keySet()))) {
      throw new IllegalArgumentException("人力池与铁路额度须属于已登记国家");
    }
    for (Country country : countries.values()) {
      String capital = country.capitalProvinceId();
      if (capital != null && !map.provinces().containsKey(capital)) {
        throw new IllegalArgumentException("国家首都省不存在: " + country.id() + " → " + capital);
      }
    }
    for (var entry : territories.entrySet()) {
      if (!map.provinces().containsKey(entry.getKey())) {
        throw new IllegalArgumentException("归属关联的省不存在: " + entry.getKey());
      }
      requireCountry(entry.getValue().owner());
      requireCountry(entry.getValue().controller());
    }
    for (var entry : transitRights.entrySet()) {
      if (!countries.containsKey(entry.getKey())) throw new IllegalArgumentException("通行授权国家不存在: " + entry.getKey());
      for (var guest : entry.getValue().entrySet()) {
        if (!countries.containsKey(guest.getKey()) || guest.getKey().equals(entry.getKey())) {
          throw new IllegalArgumentException("通行受权国家无效: " + guest.getKey());
        }
        new Assignment(entry.getKey(), guest.getValue());
      }
    }
  }

  private void requireCountry(Assignment assignment) {
    if (assignment != null && !countries.containsKey(assignment.countryId())) {
      throw new IllegalArgumentException("归属关联的国家不存在: " + assignment.countryId());
    }
  }
}
