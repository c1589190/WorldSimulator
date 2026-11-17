package io.mosire.worldsim.map;

import java.util.List;
import java.util.Objects;

/**
 * 静态地块/省份。
 *
 * <p>{@code displayPoint} 是给地图标记/标签/单位选中轮廓定位用的显示坐标；渲染器不必须渲染 polygon。
 * {@code shape} 是权威几何；所有按省查询、邻接、选中都通过稳定 {@code id} 链接到它。
 */
public record Province(
    String id,
    String name,
    String kind,
    String terrain,
    ProvinceShape shape,
    Vec2 displayPoint,
    String stateId,
    String regionId,
    List<String> tags) {

  public Province {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Province.id 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Province.name 不得为空白: " + id);
    }
    if (kind == null || !(kind.equals("land") || kind.equals("sea") || kind.equals("lake"))) {
      throw new IllegalArgumentException("Province.kind 必须是 land/sea/lake: " + kind);
    }
    if (terrain == null || terrain.isBlank()) {
      throw new IllegalArgumentException("Province.terrain 不得为空白: " + id);
    }
    Objects.requireNonNull(shape, "shape");
    Objects.requireNonNull(displayPoint, "displayPoint");
    if (stateId != null && stateId.isBlank()) {
      throw new IllegalArgumentException("Province.stateId 若给出不得为空白: " + id);
    }
    if (regionId != null && regionId.isBlank()) {
      throw new IllegalArgumentException("Province.regionId 若给出不得为空白: " + id);
    }
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) {
        throw new IllegalArgumentException("Province.tags 不得含空白项: " + id);
      }
    }
  }
}
