package io.mosire.worldsim.state;

import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.WorldMap;

import java.util.Objects;

/** 时间线保存的静态世界状态。首版只包含地图；ORBAT/INFO/动态状态后续扩展 ChangeSet。 */
public record StaticWorldState(String id, WorldMap map) {
  public StaticWorldState {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("StaticWorldState.id 不得为空白");
    }
    Objects.requireNonNull(map, "map");
  }

  public StaticWorldState withMap(WorldMap next) {
    return new StaticWorldState(id, next);
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
