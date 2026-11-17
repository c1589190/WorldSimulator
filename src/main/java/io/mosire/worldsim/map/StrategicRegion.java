package io.mosire.worldsim.map;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 战略区域：省的静态分组；首版不承载天气/空海规则。 */
public record StrategicRegion(String id, String name, List<String> provinceIds) {
  public StrategicRegion {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("StrategicRegion.id 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("StrategicRegion.name 不得为空白: " + id);
    }
    Objects.requireNonNull(provinceIds, "provinceIds");
    Set<String> unique = new LinkedHashSet<>();
    for (String provinceId : provinceIds) {
      if (provinceId == null || provinceId.isBlank()) {
        throw new IllegalArgumentException("StrategicRegion.provinceIds 不得含空白: " + id);
      }
      if (!unique.add(provinceId)) {
        throw new IllegalArgumentException("StrategicRegion.provinceIds 有重复: " + id + " / " + provinceId);
      }
    }
    provinceIds = List.copyOf(unique);
  }
}
