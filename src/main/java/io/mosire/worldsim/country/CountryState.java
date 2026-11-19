package io.mosire.worldsim.country;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.Evidence;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Country identities and province-level claims for a single timeline state. */
public record CountryState(Map<String, Country> countries, Map<String, Territory> territories) {
  public static final CountryState EMPTY = new CountryState(Map.of(), Map.of());

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

  public CountryState {
    countries = Map.copyOf(countries);
    territories = Map.copyOf(territories);
    for (var entry : countries.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("国家键与 ID 不一致");
    }
  }

  public CountryState withCountry(Country country) {
    var next = new TreeMap<>(countries);
    next.put(country.id(), country);
    return new CountryState(next, territories);
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
    return new CountryState(countries, next);
  }

  public void validate(WorldMap map) {
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
  }

  private void requireCountry(Assignment assignment) {
    if (assignment != null && !countries.containsKey(assignment.countryId())) {
      throw new IllegalArgumentException("归属关联的国家不存在: " + assignment.countryId());
    }
  }
}
