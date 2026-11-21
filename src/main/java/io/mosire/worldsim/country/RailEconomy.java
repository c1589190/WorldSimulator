package io.mosire.worldsim.country;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.SimulationTime;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The period budget and the shared rail use of the current scenario period. */
public record RailEconomy(String anchor, Rules rules, long periodIndex,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> countryUse,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> segmentUse) {

  public record Kilograms(Long kilograms, Evidence evidence) {
    public Kilograms {
      if (kilograms == null || kilograms < 0) throw new IllegalArgumentException("运力和重量须以非负整数公斤表示");
      Evidence.requireRecorded(evidence);
    }
  }

  public record Rules(int periodMinutes, Map<String, Kilograms> countryQuotas, Map<String, Kilograms> itemWeights) {
    public Rules {
      if (periodMinutes <= 0) throw new IllegalArgumentException("经济期须为正整数分钟");
      countryQuotas = countryQuotas == null ? Map.of() : Map.copyOf(countryQuotas);
      itemWeights = itemWeights == null ? Map.of() : Map.copyOf(itemWeights);
      countryQuotas.keySet().forEach(NodeAddress::requireSegment);
      for (var entry : itemWeights.entrySet()) {
        NodeAddress.requireSegment(entry.getKey());
        if (entry.getValue().kilograms() == 0) throw new IllegalArgumentException("单件装备运输重量须大于零");
      }
    }
  }

  public record Period(long index, String startsAt, String endsAt, int minutes) {}

  public record Segment(String a, String b) {
    public Segment {
      NodeAddress.requireSegment(a);
      NodeAddress.requireSegment(b);
      if (a.equals(b)) throw new IllegalArgumentException("铁路路段需要不同的两个端点");
      if (a.compareTo(b) > 0) {
        String first = a;
        a = b;
        b = first;
      }
    }

    public String key() { return a + "/" + b; }
  }

  public record Balance(long limitKg, long beforeKg, long afterKg) {
    public Balance {
      if (beforeKg < 0 || afterKg <= beforeKg || afterKg > limitKg) {
        throw new IllegalArgumentException("铁路运力变更与可用量不符");
      }
    }

    public long consumedKg() { return afterKg - beforeKg; }
  }

  public record SegmentCharge(Segment segment, String railwayId, Evidence evidence, Balance balance) {
    public SegmentCharge {
      NodeAddress.requireSegment(railwayId);
      if (segment == null || balance == null) throw new IllegalArgumentException("缺少铁路路段扣账");
      Evidence.requireRecorded(evidence);
    }
  }

  public record Charge(long periodIndex, Balance country, List<SegmentCharge> segments) {
    public Charge {
      if (periodIndex < 0 || country == null || segments == null || segments.isEmpty()) {
        throw new IllegalArgumentException("跨省交付需要经济期、国家额度及铁路路段扣账");
      }
      segments = List.copyOf(segments);
      var seen = new HashSet<Segment>();
      for (var segment : segments) {
        if (!seen.add(segment.segment()) || segment.balance().consumedKg() != country.consumedKg()) {
          throw new IllegalArgumentException("铁路路段重复或与国家扣账重量不符");
        }
      }
    }
  }

  public RailEconomy {
    anchor = SimulationTime.normalize(anchor);
    if (rules == null || periodIndex < 0) throw new IllegalArgumentException("无效的经济期配置");
    countryUse = countryUse == null ? Map.of() : Map.copyOf(countryUse);
    segmentUse = segmentUse == null ? Map.of() : Map.copyOf(segmentUse);
    countryUse.keySet().forEach(NodeAddress::requireSegment);
    for (String key : segmentUse.keySet()) {
      int split = key.indexOf('/');
      if (split < 1 || !new Segment(key.substring(0, split), key.substring(split + 1)).key().equals(key)) {
        throw new IllegalArgumentException("铁路用量须用规范的路段键: " + key);
      }
    }
    if (countryUse.values().stream().anyMatch(kg -> kg <= 0) || segmentUse.values().stream().anyMatch(kg -> kg <= 0)) {
      throw new IllegalArgumentException("铁路用量须为正整数公斤");
    }
  }

  public static RailEconomy start(String time, Rules rules) {
    return new RailEconomy(time, rules, 0, Map.of(), Map.of());
  }

  public RailEconomy withRules(Rules next) {
    if (next.periodMinutes() != rules.periodMinutes()) throw new IllegalArgumentException("已开的经济期须保留原期长");
    var quotas = new TreeMap<>(rules.countryQuotas());
    quotas.putAll(next.countryQuotas());
    var weights = new TreeMap<>(rules.itemWeights());
    weights.putAll(next.itemWeights());
    return new RailEconomy(anchor, new Rules(rules.periodMinutes(), quotas, weights), periodIndex, countryUse, segmentUse);
  }

  public RailEconomy atTime(String time) {
    long index = indexAt(time);
    if (index < periodIndex) throw new IllegalArgumentException("经济期不能回退");
    if (index == periodIndex) return this;
    return new RailEconomy(anchor, rules, index, Map.of(), Map.of());
  }

  public void validateTime(String time) {
    if (time == null || indexAt(time) != periodIndex) throw new IllegalArgumentException("当前铁路用量与剧本经济期不符");
  }

  private long indexAt(String time) {
    long elapsed = ChronoUnit.SECONDS.between(LocalDateTime.parse(anchor), LocalDateTime.parse(time));
    if (elapsed < 0) throw new IllegalArgumentException("剧本时间早于经济期起点");
    return elapsed / (rules.periodMinutes() * 60L);
  }

  private String boundary(long index) {
    var time = LocalDateTime.parse(anchor).plusMinutes(Math.multiplyExact(index, (long) rules.periodMinutes()));
    return time.getYear() > 9999 ? null : SimulationTime.format(time);
  }

  public Period period() {
    return new Period(periodIndex, boundary(periodIndex), nextBoundary(), rules.periodMinutes());
  }

  public String nextBoundary() { return boundary(periodIndex + 1); }

  public long usedByCountry(String id) { return countryUse.getOrDefault(id, 0L); }

  public long usedOnSegment(Segment segment) { return segmentUse.getOrDefault(segment.key(), 0L); }

  public RailEconomy applyCharge(String countryId, String itemId, int count, Charge charge) {
    Kilograms limit = rules.countryQuotas().get(countryId);
    Kilograms weight = rules.itemWeights().get(itemId);
    if (charge.periodIndex() != periodIndex || limit == null || weight == null
        || limit.kilograms() != charge.country().limitKg() || usedByCountry(countryId) != charge.country().beforeKg()
        || Math.multiplyExact(weight.kilograms(), count) != charge.country().consumedKg()) {
      throw new IllegalArgumentException("国家铁路扣账与当前经济期额度、重量或用量不符");
    }
    var countries = new TreeMap<>(countryUse);
    countries.put(countryId, charge.country().afterKg());
    var segments = new TreeMap<>(segmentUse);
    for (var leg : charge.segments()) {
      if (usedOnSegment(leg.segment()) != leg.balance().beforeKg()) throw new IllegalArgumentException("铁路路段扣账基数不符");
      segments.put(leg.segment().key(), leg.balance().afterKg());
    }
    return new RailEconomy(anchor, rules, periodIndex, countries, segments);
  }
}
