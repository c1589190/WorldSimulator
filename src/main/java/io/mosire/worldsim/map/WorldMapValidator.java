package io.mosire.worldsim.map;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 静态地图的引用/几何/拓扑不变量。 */
public final class WorldMapValidator {
  private static final double AREA_EPSILON = 1e-8;

  private WorldMapValidator() {}

  public static List<ValidationIssue> validate(WorldMap map) {
    Objects.requireNonNull(map, "map");
    List<ValidationIssue> issues = new ArrayList<>();
    validateReferences(map, issues);
    validateGeometry(map, issues);
    return List.copyOf(issues);
  }

  public static void requireValid(WorldMap map) {
    List<ValidationIssue> issues = validate(map);
    if (!issues.isEmpty()) {
      throw new WorldValidationException(issues);
    }
  }

  private static void validateReferences(WorldMap map, List<ValidationIssue> issues) {
    // 州：地图键 == 值 id，省存在且属于 land/lake，省反向 stateId 一致。
    for (Map.Entry<String, State> entry : map.states().entrySet()) {
      State state = entry.getValue();
      if (!entry.getKey().equals(state.id())) {
        error(issues, "STATE_KEY_ID_MISMATCH", "states key=" + entry.getKey() + " id=" + state.id());
      }
      Set<String> stateRegions = new HashSet<>();
      for (String provinceId : state.provinceIds()) {
        Province province = map.provinces().get(provinceId);
        if (province == null) {
          error(issues, "STATE_PROVINCE_MISSING", "州 " + state.id() + " 引用不存在的省 " + provinceId);
          continue;
        }
        if (province.kind().equals("sea")) {
          error(issues, "STATE_CONTAINS_SEA", "州 " + state.id() + " 不能包含海省 " + provinceId);
        }
        if (!state.id().equals(province.stateId())) {
          error(issues, "PROVINCE_STATE_MISMATCH",
              "省 " + provinceId + " stateId=" + province.stateId() + "，但州 " + state.id() + " 列了它");
        }
        if (province.regionId() != null) {
          stateRegions.add(province.regionId());
        }
      }
      if (stateRegions.size() > 1) {
        error(issues, "STATE_SPANS_REGIONS",
            "州 " + state.id() + " 的省跨越多个战略区域: " + stateRegions);
      }
    }
    for (Province province : map.provinces().values()) {
      if (province.stateId() != null) {
        State state = map.states().get(province.stateId());
        if (state == null) {
          error(issues, "PROVINCE_STATE_UNKNOWN", "省 " + province.id() + " 引用不存在的州 " + province.stateId());
        } else if (!state.provinceIds().contains(province.id())) {
          error(issues, "PROVINCE_STATE_NOT_LISTED",
              "省 " + province.id() + " 声明 stateId=" + state.id() + "，但州未列出该省");
        }
      } else if (province.kind().equals("land")) {
        error(issues, "LAND_WITHOUT_STATE", "陆省 " + province.id() + " 没有州归属");
      }
    }

    // 战略区域：每个省恰好有一个区域，区域列表反向一致。
    for (Map.Entry<String, StrategicRegion> entry : map.strategicRegions().entrySet()) {
      StrategicRegion region = entry.getValue();
      if (!entry.getKey().equals(region.id())) {
        error(issues, "REGION_KEY_ID_MISMATCH", "strategicRegions key=" + entry.getKey() + " id=" + region.id());
      }
      Set<String> seen = new HashSet<>();
      for (String provinceId : region.provinceIds()) {
        if (!seen.add(provinceId)) {
          error(issues, "REGION_DUPLICATE_PROVINCE", "区域 " + region.id() + " 重复列省 " + provinceId);
        }
        Province province = map.provinces().get(provinceId);
        if (province == null) {
          error(issues, "REGION_PROVINCE_MISSING", "区域 " + region.id() + " 引用不存在的省 " + provinceId);
        } else if (!region.id().equals(province.regionId())) {
          error(issues, "PROVINCE_REGION_MISMATCH",
              "省 " + provinceId + " regionId=" + province.regionId() + "，但区域 " + region.id() + " 列了它");
        }
      }
    }
    for (Province province : map.provinces().values()) {
      if (province.regionId() == null) {
        error(issues, "PROVINCE_REGION_MISSING", "省 " + province.id() + " 没有战略区域");
        continue;
      }
      StrategicRegion region = map.strategicRegions().get(province.regionId());
      if (region == null) {
        error(issues, "PROVINCE_REGION_UNKNOWN", "省 " + province.id() + " 引用不存在的区域 " + province.regionId());
      } else if (!region.provinceIds().contains(province.id())) {
        error(issues, "PROVINCE_REGION_NOT_LISTED",
            "省 " + province.id() + " 声明 regionId=" + region.id() + "，但区域未列出该省");
      }
    }

    // 补给中心：必须在 land province。
    for (Map.Entry<String, SupplyHub> entry : map.supplyHubs().entrySet()) {
      SupplyHub hub = entry.getValue();
      if (!entry.getKey().equals(hub.id())) {
        error(issues, "SUPPLY_KEY_ID_MISMATCH", "supplyHubs key=" + entry.getKey() + " id=" + hub.id());
      }
      Province province = map.provinces().get(hub.provinceId());
      if (province == null) {
        error(issues, "SUPPLY_PROVINCE_MISSING", "补给中心 " + hub.id() + " 引用不存在的省 " + hub.provinceId());
      } else if (!province.kind().equals("land")) {
        error(issues, "SUPPLY_ON_NON_LAND", "补给中心 " + hub.id() + " 不在陆省: " + province.kind());
      }
    }

    // EntityLink：key 与值一致；已知 map category 必须指向存在的实体。
    for (Map.Entry<String, EntityLink> entry : map.entityLinks().entrySet()) {
      EntityLink link = entry.getValue();
      if (!entry.getKey().equals(link.key())) {
        error(issues, "ENTITY_LINK_KEY_MISMATCH",
            "entityLinks key=" + entry.getKey() + " key=" + link.key());
      }
      boolean targetExists = switch (link.category()) {
        case "province" -> map.provinces().containsKey(link.targetId());
        case "state" -> map.states().containsKey(link.targetId());
        case "region" -> map.strategicRegions().containsKey(link.targetId());
        case "supplyHub" -> map.supplyHubs().containsKey(link.targetId());
        case "railway" -> map.railways().containsKey(link.targetId());
        default -> true;
      };
      if (!targetExists) {
        error(issues, "ENTITY_LINK_TARGET_MISSING",
            "EntityLink " + link.key() + " category=" + link.category()
                + " 指向不存在的 target=" + link.targetId());
      }
    }

    // 铁路：链中省存在、不是海省、每一对相邻。
    Set<String> adjacencyPairs = new HashSet<>();
    for (Adjacency adjacency : map.adjacencies()) {
      adjacencyPairs.add(adjacency.a() + "|" + adjacency.b());
      adjacencyPairs.add(adjacency.b() + "|" + adjacency.a());
    }
    for (Map.Entry<String, Railway> entry : map.railways().entrySet()) {
      Railway railway = entry.getValue();
      if (!entry.getKey().equals(railway.id())) {
        error(issues, "RAILWAY_KEY_ID_MISMATCH",
            "railways key=" + entry.getKey() + " id=" + railway.id());
      }
      List<String> path = railway.provincePath();
      for (int i = 0; i < path.size(); i++) {
        Province province = map.provinces().get(path.get(i));
        if (province == null) {
          error(issues, "RAILWAY_PROVINCE_MISSING",
              "铁路 " + railway.id() + " 引用不存在的省 " + path.get(i));
          continue;
        }
        if (province.kind().equals("sea")) {
          error(issues, "RAILWAY_ON_SEA",
              "铁路 " + railway.id() + " 经过海省 " + path.get(i));
        }
        if (i > 0) {
          String previous = path.get(i - 1);
          if (!adjacencyPairs.contains(previous + "|" + path.get(i))) {
            error(issues, "RAILWAY_NOT_ADJACENT",
                "铁路 " + railway.id() + " 的相邻省无普通邻接: " + previous + " -> " + path.get(i));
          }
        }
      }
    }

    // 邻接：两端存在、不自环、无重复无向关系。
    Set<String> seenPairs = new HashSet<>();
    for (Adjacency adjacency : map.adjacencies()) {
      if (!map.provinces().containsKey(adjacency.a())) {
        error(issues, "ADJACENCY_PROVINCE_MISSING", "邻接引用不存在的省 " + adjacency.a());
      }
      if (!map.provinces().containsKey(adjacency.b())) {
        error(issues, "ADJACENCY_PROVINCE_MISSING", "邻接引用不存在的省 " + adjacency.b());
      }
      if (adjacency.through() != null && !map.provinces().containsKey(adjacency.through())) {
        error(issues, "ADJACENCY_THROUGH_MISSING",
            "邻接 " + adjacency.a() + "<->" + adjacency.b()
                + " 的 through 省不存在: " + adjacency.through());
      }
      String pair = adjacency.a() + "|" + adjacency.b() + "|" + adjacency.kind()
          + "|" + adjacency.through();
      if (!seenPairs.add(pair)) {
        error(issues, "ADJACENCY_DUPLICATE", "重复邻接 " + pair);
      }
    }
  }

  private static void validateGeometry(WorldMap map, List<ValidationIssue> issues) {
    Map<String, Geometry> geometries = new HashMap<>();
    for (Province province : map.provinces().values()) {
      Geometry geometry;
      try {
        geometry = JtsGeometry.toGeometry(province.shape());
      } catch (RuntimeException e) {
        error(issues, "POLYGON_BUILD_FAILED", "省 " + province.id() + " 无法构造 polygon: " + e.getMessage());
        continue;
      }
      if (geometry.isEmpty()) {
        error(issues, "POLYGON_EMPTY", "省 " + province.id() + " 的几何为空");
        continue;
      }
      if (!geometry.isValid()) {
        error(issues, "POLYGON_INVALID", "省 " + province.id() + " 的 polygon 拓扑非法");
      }
      if (geometry.getArea() <= AREA_EPSILON) {
        error(issues, "POLYGON_ZERO_AREA", "省 " + province.id() + " 的面积过小: " + geometry.getArea());
      }
      Envelope envelope = geometry.getEnvelopeInternal();
      if (envelope.getMinX() < 0 || envelope.getMinY() < 0
          || envelope.getMaxX() > map.width() || envelope.getMaxY() > map.height()) {
        error(issues, "POLYGON_OUT_OF_BOUNDS", "省 " + province.id() + " 超出世界范围");
      }
      if (!geometry.covers(JtsGeometry.point(province.displayPoint()))) {
        error(issues, "DISPLAY_POINT_OUTSIDE", "省 " + province.id() + " 的显示坐标不在 polygon 内");
      }
      geometries.put(province.id(), geometry);
    }
    // STRtree 邻域查询，避免 13k 省的 O(n^2) 全量相交。
    STRtree index = new STRtree();
    for (Map.Entry<String, Geometry> entry : geometries.entrySet()) {
      index.insert(entry.getValue().getEnvelopeInternal(), new GeometryItem(entry.getKey(), entry.getValue()));
    }
    index.build();
    for (GeometryItem item : geometries.entrySet().stream()
        .map(e -> new GeometryItem(e.getKey(), e.getValue())).toList()) {
      @SuppressWarnings("unchecked")
      List<GeometryItem> hits = (List<GeometryItem>) index.query(item.geometry().getEnvelopeInternal());
      for (GeometryItem other : hits) {
        if (item.id().compareTo(other.id()) >= 0) {
          continue;
        }
        try {
          if (item.geometry().intersection(other.geometry()).getArea() > AREA_EPSILON) {
            error(issues, "POLYGON_OVERLAP", "省 " + item.id() + " 与 " + other.id() + " 面积重叠");
          }
        } catch (RuntimeException e) {
          error(issues, "POLYGON_INTERSECTION_FAILED",
              "省 " + item.id() + " 与 " + other.id() + " 相交计算失败: " + e.getMessage());
        }
      }
    }
  }

  private static void error(List<ValidationIssue> issues, String code, String message) {
    issues.add(new ValidationIssue(ValidationSeverity.ERROR, code, message));
  }

  private record GeometryItem(String id, Geometry geometry) {}
}
