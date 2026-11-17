package io.mosire.worldsim.map;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 州的静态定义：省归属与显示名；owner/资源等动态语义不在这里硬编码。 */
public record State(String id, String name, List<String> provinceIds) {
  public State {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("State.id 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("State.name 不得为空白: " + id);
    }
    Objects.requireNonNull(provinceIds, "provinceIds");
    Set<String> unique = new LinkedHashSet<>();
    for (String provinceId : provinceIds) {
      if (provinceId == null || provinceId.isBlank()) {
        throw new IllegalArgumentException("State.provinceIds 不得含空白: " + id);
      }
      if (!unique.add(provinceId)) {
        throw new IllegalArgumentException("State.provinceIds 有重复: " + id + " / " + provinceId);
      }
    }
    provinceIds = List.copyOf(unique);
  }
}
