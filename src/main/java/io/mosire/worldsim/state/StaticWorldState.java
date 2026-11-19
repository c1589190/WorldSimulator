package io.mosire.worldsim.state;

import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.orbat.OrbatState;

import java.util.Objects;

/** Saved world state. Empty optional domains preserve the original map-only canonical bytes. */
public record StaticWorldState(String id, WorldMap map, CountryState country, OrbatState orbat) {
  public StaticWorldState {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("StaticWorldState.id 不得为空白");
    }
    Objects.requireNonNull(map, "map");
    if (CountryState.EMPTY.equals(country)) country = null;
    if (OrbatState.EMPTY.equals(orbat)) orbat = null;
  }

  public StaticWorldState(String id, WorldMap map) {
    this(id, map, null, null);
  }

  public StaticWorldState withMap(WorldMap next) {
    return new StaticWorldState(id, next, country, orbat);
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
}
