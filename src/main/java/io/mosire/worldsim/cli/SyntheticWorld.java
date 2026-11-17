package io.mosire.worldsim.cli;

import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Ring;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.StaticWorldState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可重复生成的矩形 polygon 网格世界，用于规模烟测/benchmark。
 *
 * <p>这不是正式地图生成器：省形是共享边的矩形，州按行、战略区域按行块分组。
 * 它专门压测 province 数量、JTS 校验、邻接推导、ChangeSet/checkpoint 回放。
 */
public final class SyntheticWorld {
  public static final double DEFAULT_CELL_SIZE = 100.0;

  private SyntheticWorld() {}

  public static StaticWorldState create(int columns, int rows) {
    return create(columns, rows, DEFAULT_CELL_SIZE);
  }

  public static StaticWorldState create(int columns, int rows, double cellSize) {
    if (columns < 1 || rows < 1) {
      throw new IllegalArgumentException("columns/rows 必须 >= 1: " + columns + "x" + rows);
    }
    if (columns > 500 || rows > 500) {
      throw new IllegalArgumentException("合成世界上限 500x500: " + columns + "x" + rows);
    }
    if (!Double.isFinite(cellSize) || cellSize <= 0) {
      throw new IllegalArgumentException("cellSize 必须是正有限数: " + cellSize);
    }
    double width = columns * cellSize;
    double height = rows * cellSize;
    if (!Double.isFinite(width) || !Double.isFinite(height)) {
      throw new IllegalArgumentException("合成世界尺寸溢出: " + columns + "x" + rows + " cell=" + cellSize);
    }

    int regionCount = Math.min(5, rows);
    Map<String, List<String>> stateProvinceIds = new LinkedHashMap<>();
    Map<String, List<String>> regionProvinceIds = new LinkedHashMap<>();
    WorldMap.Builder builder = WorldMap.builder(
        "synthetic-" + columns + "x" + rows, "Synthetic " + columns + "x" + rows, width, height);

    for (int row = 0; row < rows; row++) {
      String stateId = "state-" + row;
      int regionIndex = (int) ((long) row * regionCount / rows);
      String regionId = "region-" + regionIndex;
      stateProvinceIds.put(stateId, new ArrayList<>());
      regionProvinceIds.computeIfAbsent(regionId, ignored -> new ArrayList<>());
      for (int column = 0; column < columns; column++) {
        String provinceId = "p-" + row + "-" + column;
        double minX = column * cellSize;
        double minY = row * cellSize;
        double maxX = minX + cellSize;
        double maxY = minY + cellSize;
        Province province = new Province(
            provinceId,
            "P " + (row + 1) + "," + (column + 1),
            "land",
            "plains",
            ProvinceShape.of(square(minX, minY, maxX, maxY)),
            new Vec2((minX + maxX) / 2.0, (minY + maxY) / 2.0),
            stateId,
            regionId,
            List.of());
        builder.province(province);
        stateProvinceIds.get(stateId).add(provinceId);
        regionProvinceIds.get(regionId).add(provinceId);
      }
    }

    stateProvinceIds.forEach((stateId, provinceIds) ->
        builder.state(new State(stateId, "State " + stateId.substring("state-".length()), provinceIds)));
    regionProvinceIds.forEach((regionId, provinceIds) ->
        builder.strategicRegion(new StrategicRegion(
            regionId, "Region " + regionId.substring("region-".length()), provinceIds)));
    builder.supplyHub(new SupplyHub("depot-start", "p-0-0", 3));
    WorldMap map = builder.deriveAdjacencies().build();
    return new StaticWorldState(map.id(), map);
  }

  private static Ring square(double minX, double minY, double maxX, double maxY) {
    return new Ring(List.of(
        new Vec2(minX, minY),
        new Vec2(maxX, minY),
        new Vec2(maxX, maxY),
        new Vec2(minX, maxY)));
  }
}
