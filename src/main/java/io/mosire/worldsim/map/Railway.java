package io.mosire.worldsim.map;

import java.util.List;
import java.util.Objects;

/** 静态铁路：等级 + 连续 provincePath；连通性由 WorldMapValidator 对照邻接校验。 */
public record Railway(String id, int level, List<String> provincePath) {
  public Railway {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Railway.id 不得为空白");
    }
    if (level < 1) {
      throw new IllegalArgumentException("Railway.level 必须 >= 1: " + level);
    }
    Objects.requireNonNull(provincePath, "provincePath");
    if (provincePath.size() < 2) {
      throw new IllegalArgumentException("Railway.provincePath 至少需要 2 个省: " + id);
    }
    for (String provinceId : provincePath) {
      if (provinceId == null || provinceId.isBlank()) {
        throw new IllegalArgumentException("Railway.provincePath 不得含空白: " + id);
      }
    }
    for (int i = 1; i < provincePath.size(); i++) {
      if (provincePath.get(i - 1).equals(provincePath.get(i))) {
        throw new IllegalArgumentException("Railway.provincePath 不得连续重复同一省: " + id);
      }
    }
    provincePath = List.copyOf(provincePath);
  }
}
