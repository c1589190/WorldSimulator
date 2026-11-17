package io.mosire.worldsim.map;

/** 省级补给中心（静态位置与等级；补给连通性/容量是运行时派生）。 */
public record SupplyHub(String id, String provinceId, int level) {
  public SupplyHub {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("SupplyHub.id 不得为空白");
    }
    if (provinceId == null || provinceId.isBlank()) {
      throw new IllegalArgumentException("SupplyHub.provinceId 不得为空白: " + id);
    }
    if (level < 0) {
      throw new IllegalArgumentException("SupplyHub.level 必须 >= 0: " + level);
    }
  }
}
