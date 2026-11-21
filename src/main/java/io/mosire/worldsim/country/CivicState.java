package io.mosire.worldsim.country;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.SimulationTime;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record CivicState(Map<String, Politics> politics, Map<String, Census> census, Map<String, Treaty> treaties) {
  public static final CivicState EMPTY = new CivicState(null, null, null);

  public CivicState {
    politics = politics == null ? Map.of() : Map.copyOf(politics);
    census = census == null ? Map.of() : Map.copyOf(census);
    treaties = treaties == null ? Map.of() : Map.copyOf(treaties);
    politics.keySet().forEach(NodeAddress::requireSegment);
    census.forEach((key, record) -> {
      if (!key.equals(record.key())) throw new IllegalArgumentException("人口键与记录不一致");
    });
    treaties.forEach((key, record) -> {
      if (!key.equals(record.id())) throw new IllegalArgumentException("条约键与 ID 不一致");
    });
  }

  public record Politics(String regime, String government, String mobilization, Evidence evidence) {
    public Politics {
      required(regime);
      required(government);
      required(mobilization);
      Evidence.requireRecorded(evidence);
    }
  }

  public record Census(String provinceId, String date, String basis, Long people, Evidence evidence) {
    public Census {
      NodeAddress.requireSegment(provinceId);
      date = LocalDate.parse(required(date)).toString();
      NodeAddress.requireSegment(basis);
      if (people == null || people < 0 || people > Integer.MAX_VALUE) throw new IllegalArgumentException("省人口须为 0 至 2147483647");
      Evidence.requireRecorded(evidence);
    }

    public String key() { return provinceId + "/" + date + "/" + basis; }
  }

  public record Treaty(String id, String type, String first, String second, String startsAt, String endsAt,
                       String terms, Evidence evidence) {
    public Treaty {
      NodeAddress.requireSegment(id);
      NodeAddress.requireSegment(first);
      NodeAddress.requireSegment(second);
      if (first.equals(second) || type == null || !Set.of("war", "ceasefire", "alliance", "transit", "agreement").contains(type)) {
        throw new IllegalArgumentException("条约需要两个不同国家和有效类型");
      }
      if (Set.of("war", "ceasefire", "alliance").contains(type) && first.compareTo(second) > 0) {
        String previous = first;
        first = second;
        second = previous;
      }
      startsAt = SimulationTime.normalize(startsAt);
      if (endsAt != null) {
        endsAt = SimulationTime.normalize(endsAt);
        if (endsAt.compareTo(startsAt) <= 0) throw new IllegalArgumentException("条约结束须晚于起始");
      }
      required(terms);
      Evidence.requireRecorded(evidence);
    }

    public String statusAt(String time) {
      if (time == null) return "unknown";
      if (time.compareTo(startsAt) < 0) return "scheduled";
      return endsAt != null && time.compareTo(endsAt) >= 0 ? "expired" : "active";
    }
  }

  public record Boundary(String treatyId, String status) {}

  public String nextBoundary(String time) {
    if (time == null) return null;
    return treaties.values().stream().flatMap(treaty -> java.util.stream.Stream.of(treaty.startsAt(), treaty.endsAt()))
        .filter(boundary -> boundary != null && boundary.compareTo(time) > 0).min(String::compareTo).orElse(null);
  }

  public List<Boundary> boundariesAt(String previous, String time) {
    if (previous != null && previous.equals(time)) return List.of();
    return treaties.values().stream().filter(treaty -> time.equals(treaty.startsAt()) || time.equals(treaty.endsAt()))
        .sorted(java.util.Comparator.comparing(Treaty::id)).map(treaty -> new Boundary(treaty.id(), treaty.statusAt(time))).toList();
  }

  public CivicState withPolitics(String country, Politics value) {
    return new CivicState(put(politics, country, value), census, treaties);
  }

  public CivicState withCensus(List<Census> records) {
    var copy = new TreeMap<>(census);
    var keys = new java.util.HashSet<String>();
    for (var record : records) {
      if (!keys.add(record.key())) throw new IllegalArgumentException("同一批次的同口径人口记录重复");
      copy.put(record.key(), record);
    }
    return new CivicState(politics, copy, treaties);
  }

  public CivicState withTreaty(Treaty treaty) {
    return new CivicState(politics, census, put(treaties, treaty.id(), treaty));
  }

  private static <T> Map<String, T> put(Map<String, T> source, String key, T value) {
    var next = new TreeMap<>(source);
    next.put(key, value);
    return next;
  }

  public void validate(WorldMap map, CountryState country) {
    var ids = country.countries().keySet();
    if (!ids.containsAll(politics.keySet())) throw new IllegalArgumentException("政治记录引用未知国家");
    for (var treaty : treaties.values()) {
      if (!ids.contains(treaty.first()) || !ids.contains(treaty.second())) throw new IllegalArgumentException("条约引用未知国家");
    }
    for (var record : census.values()) {
      var province = map.provinces().get(record.provinceId());
      if (province == null || !province.kind().equals("land")) throw new IllegalArgumentException("人口记录需要陆省: " + record.provinceId());
    }
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少文本字段");
    return value;
  }
}
