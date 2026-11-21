package io.mosire.worldsim.map;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.state.Evidence;
import java.util.Set;

/** A physical facility or resource occurrence in a single province. */
public record MapFeature(String id, String name, String kind, String provinceId, String waterProvinceId,
                         Integer level, String operation, String commodity, Double quantity, String unit,
                         Evidence evidence) {
  public MapFeature {
    NodeAddress.requireSegment(id);
    NodeAddress.requireSegment(provinceId);
    if (name == null || name.isBlank() || evidence == null) throw new IllegalArgumentException("地图标记需要名称及证据");
    switch (kind) {
      case "port" -> {
        if (waterProvinceId != null) NodeAddress.requireSegment(waterProvinceId);
        if (level != null && level < 0 || commodity != null || quantity != null || unit != null
            || operation != null && !Set.of("open", "closed", "unknown").contains(operation)) {
          throw new IllegalArgumentException("港口字段无效");
        }
      }
      case "resource" -> {
        if (waterProvinceId != null || level != null || commodity == null || commodity.isBlank()
            || operation != null && !Set.of("active", "inactive", "unknown").contains(operation)
            || quantity != null && (!Double.isFinite(quantity) || quantity < 0 || unit == null || unit.isBlank())) {
          throw new IllegalArgumentException("资源字段无效");
        }
      }
      case null, default -> throw new IllegalArgumentException("地图标记类型须为 port 或 resource");
    }
  }
}
