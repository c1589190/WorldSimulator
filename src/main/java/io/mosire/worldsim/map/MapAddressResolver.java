package io.mosire.worldsim.map;

import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.address.NodeAddress;

import java.util.List;
import java.util.Objects;

/**
 * 静态地图地址解析：{@code <node>/map/{type}/{id}}。
 *
 * <p>地址是定位方式，稳定 id 是身份。本类只解析 map 模块实体；未知类型/不存在实体返回稳定错误码。
 * 支持：province、state、region、supplyHub。
 */
public final class MapAddressResolver {
  private MapAddressResolver() {}

  public static ResolvedEntity resolve(WorldMap map, NodeAddress address) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(address, "address");
    List<String> path = address.requireEntityPath().path();
    if (!path.get(0).equals("map")) {
      throw new AddressException("MAP_ADDRESS_UNSUPPORTED",
          "不是地图模块地址（首段必须是 map）: " + address.canonical());
    }
    if (path.size() < 2) {
      throw new AddressException("MAP_ENTITY_TYPE_MISSING",
          "地图地址缺少实体类型: " + address.canonical());
    }
    String type = path.get(1);
    return switch (type) {
      case "province" -> {
        String id = requireSingleId(path, address);
        Province value = map.provinces().get(id);
        if (value == null) {
          throw notFound("province", id, address);
        }
        yield new ResolvedEntity("province", "province:" + id, value.name(), value);
      }
      case "state" -> {
        String id = requireSingleId(path, address);
        State value = map.states().get(id);
        if (value == null) {
          throw notFound("state", id, address);
        }
        yield new ResolvedEntity("state", "state:" + id, value.name(), value);
      }
      case "region" -> {
        String id = requireSingleId(path, address);
        StrategicRegion value = map.strategicRegions().get(id);
        if (value == null) {
          throw notFound("region", id, address);
        }
        yield new ResolvedEntity("region", "region:" + id, value.name(), value);
      }
      case "supplyHub" -> {
        String id = requireSingleId(path, address);
        SupplyHub value = map.supplyHubs().get(id);
        if (value == null) {
          throw notFound("supplyHub", id, address);
        }
        yield new ResolvedEntity("supplyHub", "supplyHub:" + id, id, value);
      }
      default -> throw new AddressException("MAP_ENTITY_UNKNOWN_TYPE",
          "未知地图实体类型 `" + type + "`: " + address.canonical());
    };
  }

  private static String requireSingleId(List<String> path, NodeAddress address) {
    if (path.size() < 3) {
      throw new AddressException("MAP_ENTITY_ID_MISSING",
          "地图实体地址缺少 id: " + address.canonical());
    }
    if (path.size() > 3) {
      throw new AddressException("MAP_ENTITY_PATH_TRAILING",
          "地图实体地址有多余路径段: " + address.canonical());
    }
    return path.get(2);
  }

  private static AddressException notFound(String type, String id, NodeAddress address) {
    return new AddressException("MAP_ENTITY_NOT_FOUND",
        "地图实体不存在: type=" + type + " id=" + id + " address=" + address.canonical());
  }

  /** 解析结果：kind + stableId + 显示名 + 原始实体对象。 */
  public record ResolvedEntity(String kind, String stableId, String displayName, Object value) {
    public ResolvedEntity {
      if (kind == null || kind.isBlank()) {
        throw new IllegalArgumentException("ResolvedEntity.kind 不得为空白");
      }
      if (stableId == null || stableId.isBlank()) {
        throw new IllegalArgumentException("ResolvedEntity.stableId 不得为空白");
      }
      if (displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("ResolvedEntity.displayName 不得为空白");
      }
      Objects.requireNonNull(value, "value");
    }
  }
}
