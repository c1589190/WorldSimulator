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

import java.util.List;
import java.util.Objects;

/** 可重复构建的 200×100 小世界：两个共享边界的 polygon 省、一个州/区域、一个补给中心。 */
public final class SampleWorld {
  public static final String WORLD_ID = "demo-world";
  public static final String WEST = "west";
  public static final String EAST = "east";
  public static final String STATE = "heartland";
  public static final String REGION = "core";

  private SampleWorld() {}

  public static StaticWorldState create() {
    return new StaticWorldState(WORLD_ID, createMap());
  }

  public static WorldMap createMap() {
    WorldMap.Builder builder = WorldMap.builder(WORLD_ID, "Headless Demo", 200, 100)
        .province(province(WEST, "West", ringA(), new Vec2(50, 50)))
        .province(province(EAST, "East", ringB(), new Vec2(150, 50)))
        .state(new State(STATE, "Heartland", List.of(WEST, EAST)))
        .strategicRegion(new StrategicRegion(REGION, "Core", List.of(WEST, EAST)))
        .supplyHub(new SupplyHub("depot-west", WEST, 3));
    return builder.deriveAdjacencies().build();
  }

  public static Province renameProvince(WorldMap map, String provinceId, String newName) {
    Objects.requireNonNull(map, "map");
    Province current = map.provinces().get(provinceId);
    if (current == null) {
      throw new IllegalArgumentException("未知省份: " + provinceId);
    }
    return new Province(
        current.id(),
        newName,
        current.kind(),
        current.terrain(),
        current.shape(),
        current.displayPoint(),
        current.stateId(),
        current.regionId(),
        current.tags());
  }

  public static Province province(String id, String name, Ring ring, Vec2 displayPoint) {
    return new Province(id, name, "land", "plains", ProvinceShape.of(ring), displayPoint,
        STATE, REGION, List.of());
  }

  public static Ring ringA() {
    return new Ring(List.of(
        new Vec2(0, 0),
        new Vec2(100, 0),
        new Vec2(100, 100),
        new Vec2(0, 100)));
  }

  public static Ring ringB() {
    return new Ring(List.of(
        new Vec2(100, 0),
        new Vec2(200, 0),
        new Vec2(200, 100),
        new Vec2(100, 100)));
  }
}
