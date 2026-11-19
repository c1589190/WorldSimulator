package io.mosire.worldsim.orbat;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.SimulationTime;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** An issued route with the estimated distance and travel time of each leg fixed at departure. */
public record March(String startedAt, double speedKmPerHour, Evidence evidence, List<Leg> legs, String cancelledAt,
                    Blockage blockage) {
  public record Supply(String status, String sourceProvinceId, String hubId) {}

  public record Conditions(String rule, String terrain, boolean terrainKnown, double terrainFactor,
                           Supply supply, double supplyFactor, CountryState.Passage passage, Double effectiveSpeedKmPerHour) {}

  public record Blockage(String time, int legIndex, CountryState.Passage passage) {
    public Blockage {
      time = SimulationTime.normalize(time);
      if (legIndex < 0 || passage == null || !passage.blocked()) throw new IllegalArgumentException("无效的通行受阻记录");
    }
  }

  public record Leg(String from, String to, double distanceKm, long seconds, Conditions conditions) {
    public Leg {
      NodeAddress.requireSegment(from);
      NodeAddress.requireSegment(to);
      if (from.equals(to) || !Double.isFinite(distanceKm) || distanceKm < 0 || seconds < 1) {
        throw new IllegalArgumentException("无效行军路段");
      }
    }

    public Leg(String from, String to, double distanceKm, long seconds) {
      this(from, to, distanceKm, seconds, null);
    }
  }

  public record Progress(String status, int reachedLegs, double fraction, String provinceId) {
    public boolean moving() { return status.equals("moving"); }
  }

  public March {
    startedAt = SimulationTime.normalize(startedAt);
    if (!Double.isFinite(speedKmPerHour) || speedKmPerHour <= 0) throw new IllegalArgumentException("行军速度须为正数");
    Objects.requireNonNull(evidence, "行军估算来源");
    legs = List.copyOf(legs);
    if (legs.isEmpty()) throw new IllegalArgumentException("行军至少需要一段路线");
    for (int i = 1; i < legs.size(); i++) {
      if (!legs.get(i - 1).to().equals(legs.get(i).from())) throw new IllegalArgumentException("行军路线须连续");
    }
    String arrival = arrivalTime(startedAt, legs);
    if (cancelledAt != null) {
      cancelledAt = SimulationTime.normalize(cancelledAt);
      if (cancelledAt.compareTo(startedAt) < 0 || cancelledAt.compareTo(arrival) >= 0) {
        throw new IllegalArgumentException("取消时间须在行军途中");
      }
    }
    if (blockage != null && (cancelledAt != null || blockage.legIndex() >= legs.size()
        || legs.get(blockage.legIndex()).conditions() == null
        || !blockage.time().equals(arrivalTime(startedAt, legs.subList(0, blockage.legIndex() + 1))))) {
      throw new IllegalArgumentException("通行受阻记录与行军路线不一致");
    }
  }

  public March(String startedAt, double speedKmPerHour, Evidence evidence, List<Leg> legs, String cancelledAt) {
    this(startedAt, speedKmPerHour, evidence, legs, cancelledAt, null);
  }

  public String arrivalTime() {
    return arrivalTime(startedAt, legs);
  }

  private static String arrivalTime(String startedAt, List<Leg> legs) {
    try {
      long seconds = 0;
      for (var leg : legs) seconds = Math.addExact(seconds, leg.seconds());
      return SimulationTime.format(LocalDateTime.parse(startedAt).plusSeconds(seconds));
    } catch (DateTimeException | ArithmeticException e) {
      throw new IllegalArgumentException("行军预计抵达时间超出范围", e);
    }
  }

  public Progress progress(String time) {
    if (time == null || time.compareTo(startedAt) < 0 || (cancelledAt != null && time.compareTo(cancelledAt) < 0)) {
      throw new IllegalArgumentException("行军记录与剧本时间不一致");
    }
    if (blockage != null) {
      if (time.compareTo(blockage.time()) < 0) throw new IllegalArgumentException("通行受阻时间晚于当前时钟");
      return new Progress("blocked", blockage.legIndex(), 0, legs.get(blockage.legIndex()).from());
    }
    long remaining = ChronoUnit.SECONDS.between(LocalDateTime.parse(startedAt),
        LocalDateTime.parse(cancelledAt == null ? time : cancelledAt));
    for (int i = 0; i < legs.size(); i++) {
      var leg = legs.get(i);
      if (remaining < leg.seconds()) {
        return new Progress(cancelledAt == null ? "moving" : "cancelled", i, (double) remaining / leg.seconds(), leg.from());
      }
      remaining -= leg.seconds();
    }
    return new Progress("arrived", legs.size(), 0, legs.getLast().to());
  }

  public March cancel(String time) {
    if (!progress(time).moving()) throw new IllegalArgumentException("该单位当前未在行军");
    return new March(startedAt, speedKmPerHour, evidence, legs, time);
  }

  March block(int leg, CountryState.Passage passage) {
    var stop = new Blockage(arrivalTime(startedAt, legs.subList(0, leg + 1)), leg, passage);
    return new March(startedAt, speedKmPerHour, evidence, legs, null, stop);
  }
}
