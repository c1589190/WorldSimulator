package io.mosire.worldsim.state;

import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.timeline.SimulationTime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

/** Saved world state. Empty optional domains preserve the original map-only canonical bytes. */
public record StaticWorldState(String id, WorldMap map, CountryState country, OrbatState orbat, String simulationTime) {
  public StaticWorldState {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("StaticWorldState.id 不得为空白");
    }
    Objects.requireNonNull(map, "map");
    if (CountryState.EMPTY.equals(country)) country = null;
    if (OrbatState.EMPTY.equals(orbat)) orbat = null;
    if (simulationTime != null) simulationTime = SimulationTime.normalize(simulationTime);
  }

  public StaticWorldState(String id, WorldMap map, CountryState country, OrbatState orbat) {
    this(id, map, country, orbat, null);
  }

  public StaticWorldState(String id, WorldMap map) {
    this(id, map, null, null);
  }

  public StaticWorldState withMap(WorldMap next) {
    return new StaticWorldState(id, next, country, orbat, simulationTime);
  }

  public CountryState countries() {
    return country == null ? CountryState.EMPTY : country;
  }

  public OrbatState formations() {
    return orbat == null ? OrbatState.EMPTY : orbat;
  }

  public String canonicalJson() {
    return JsonCodec.canonicalJson(this);
  }

  public static StaticWorldState fromJson(String json) {
    return JsonCodec.fromJson(json, StaticWorldState.class);
  }

  /** 内容哈希由规范 JSON 派生；同一内容应得到同一 hash。 */
  public String contentHash() {
    return JsonCodec.sha256(canonicalJson());
  }

  /** Reuses the canonical JSON prefix for states sharing this immutable map. */
  public Function<StaticWorldState, String> contentHasher() {
    MessageDigest prefix;
    try {
      prefix = MessageDigest.getInstance("SHA-256");
      prefix.clone();
    } catch (NoSuchAlgorithmException | CloneNotSupportedException e) {
      return StaticWorldState::contentHash;
    }
    String mapJson = new StaticWorldState(id, map).canonicalJson();
    prefix.update(mapJson.substring(0, mapJson.length() - 1).getBytes(StandardCharsets.UTF_8));
    return state -> {
      if (!id.equals(state.id()) || map != state.map()) return state.contentHash();
      String fields = JsonCodec.canonicalJson(new StateFields(state.country(), state.orbat(), state.simulationTime()));
      try {
        var digest = (MessageDigest) prefix.clone();
        if (fields.length() > 2) digest.update((byte) ',');
        return "sha256:" + HexFormat.of().formatHex(digest.digest(fields.substring(1).getBytes(StandardCharsets.UTF_8)));
      } catch (CloneNotSupportedException e) {
        return state.contentHash();
      }
    };
  }

  // These fields follow id and map in the canonical state JSON.
  private record StateFields(CountryState country, OrbatState orbat, String simulationTime) {}
}
