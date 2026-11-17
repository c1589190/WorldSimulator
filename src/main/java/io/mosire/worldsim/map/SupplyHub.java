package io.mosire.worldsim.map;

/**
 * 省级补给中心（静态位置与等级）。
 *
 * <p>{@code capacity}/{@code range} 允许为 {@code null}：源文件只给出节点位置时，未知就是未知，
 * 不用 0 或猜测值冒充容量/范围。
 */
public record SupplyHub(String id, String provinceId, int level, Double capacity, Double range) {
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
    if (capacity != null && (!Double.isFinite(capacity) || capacity < 0)) {
      throw new IllegalArgumentException("SupplyHub.capacity 必须是非负有限数或 null: " + capacity);
    }
    if (range != null && (!Double.isFinite(range) || range < 0)) {
      throw new IllegalArgumentException("SupplyHub.range 必须是非负有限数或 null: " + range);
    }
  }

  public SupplyHub(String id, String provinceId, int level) {
    this(id, provinceId, level, null, null);
  }
}
