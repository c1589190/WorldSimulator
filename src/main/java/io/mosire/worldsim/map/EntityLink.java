package io.mosire.worldsim.map;

import java.util.List;
import java.util.Objects;

/**
 * 稳定实体标识到地图实体/显示坐标的链接记录。
 *
 * <p>地址是定位方式，稳定 key 是身份；unit/custom 等未来模块可以只提供 key/target，
 * 不要求 map 模块理解其内部结构。
 */
public record EntityLink(String key, String category, String targetId, Vec2 displayPoint, List<String> tags) {
  public EntityLink {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("EntityLink.key 不得为空白");
    }
    if (category == null || category.isBlank()) {
      throw new IllegalArgumentException("EntityLink.category 不得为空白: " + key);
    }
    if (targetId == null || targetId.isBlank()) {
      throw new IllegalArgumentException("EntityLink.targetId 不得为空白: " + key);
    }
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) {
        throw new IllegalArgumentException("EntityLink.tags 不得含空白: " + key);
      }
    }
  }

  public EntityLink(String key, String category, String targetId) {
    this(key, category, targetId, null, List.of());
  }
}
