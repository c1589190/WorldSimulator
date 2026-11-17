package io.mosire.worldsim.map;

import io.mosire.worldsim.cli.SampleWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMapValidatorTest {
  @Test
  void sampleWorldIsValidAndDerivesAdjacency() {
    WorldMap map = SampleWorld.createMap();
    assertTrue(WorldMapValidator.validate(map).isEmpty());
    assertEquals(1, map.adjacencies().size());
    assertEquals(new Adjacency(SampleWorld.EAST, SampleWorld.WEST, "land", 100),
        map.adjacencies().get(0));
  }

  @Test
  void detectsOverlappingPolygons() {
    WorldMap map = baseMap("overlap")
        .province(province("a", square(0, 0, 100, 100), new Vec2(50, 50)))
        .province(province("b", square(50, 50, 150, 150), new Vec2(100, 100)))
        .state(new State("s", "S", List.of("a", "b")))
        .strategicRegion(new StrategicRegion("r", "R", List.of("a", "b")))
        .build();

    assertIssue(map, "POLYGON_OVERLAP");
  }

  @Test
  void detectsDisplayPointOutsidePolygon() {
    WorldMap map = baseMap("point")
        .province(new Province("a", "A", "land", "plains", ProvinceShape.of(square(0, 0, 100, 100)),
            new Vec2(500, 500), "s", "r", List.of()))
        .state(new State("s", "S", List.of("a")))
        .strategicRegion(new StrategicRegion("r", "R", List.of("a")))
        .build();

    assertIssue(map, "DISPLAY_POINT_OUTSIDE");
  }

  @Test
  void detectsSelfIntersection() {
    Ring bowTie = new Ring(List.of(
        new Vec2(0, 0), new Vec2(100, 100), new Vec2(0, 100), new Vec2(100, 0)));
    WorldMap map = baseMap("bowtie")
        .province(new Province("a", "A", "land", "plains", ProvinceShape.of(bowTie),
            new Vec2(50, 50), "s", "r", List.of()))
        .state(new State("s", "S", List.of("a")))
        .strategicRegion(new StrategicRegion("r", "R", List.of("a")))
        .build();

    assertIssue(map, "POLYGON_INVALID");
  }

  @Test
  void detectsLandWithoutStateAndOutOfBounds() {
    WorldMap map = baseMap("bad")
        .province(new Province("a", "A", "land", "plains", ProvinceShape.of(square(190, 90, 260, 160)),
            new Vec2(200, 100), null, "r", List.of()))
        .strategicRegion(new StrategicRegion("r", "R", List.of("a")))
        .build();

    assertIssue(map, "LAND_WITHOUT_STATE");
    assertIssue(map, "POLYGON_OUT_OF_BOUNDS");
  }

  @Test
  void detectsStateContainingSeaAndSupplyHubOnSea() {
    WorldMap map = baseMap("sea")
        .province(new Province("a", "A", "sea", "ocean", ProvinceShape.of(square(0, 0, 100, 100)),
            new Vec2(50, 50), null, "r", List.of()))
        .state(new State("s", "S", List.of("a")))
        .strategicRegion(new StrategicRegion("r", "R", List.of("a")))
        .supplyHub(new SupplyHub("h", "a", 1))
        .build();

    assertIssue(map, "STATE_CONTAINS_SEA");
    assertIssue(map, "SUPPLY_ON_NON_LAND");
  }

  @Test
  void detectsReferenceMismatch() {
    WorldMap map = baseMap("refs")
        .province(province("a", square(0, 0, 100, 100), new Vec2(50, 50)))
        .state(new State("s", "S", List.of()))
        .strategicRegion(new StrategicRegion("r", "R", List.of()))
        .build();

    assertIssue(map, "PROVINCE_STATE_NOT_LISTED");
    assertIssue(map, "PROVINCE_REGION_NOT_LISTED");
  }

  private static WorldMap.Builder baseMap(String id) {
    return WorldMap.builder(id, "Test", 200, 100);
  }

  private static Province province(String id, Ring ring, Vec2 displayPoint) {
    return new Province(id, id, "land", "plains", ProvinceShape.of(ring), displayPoint,
        "s", "r", List.of());
  }

  private static Ring square(double minX, double minY, double maxX, double maxY) {
    return new Ring(List.of(
        new Vec2(minX, minY),
        new Vec2(maxX, minY),
        new Vec2(maxX, maxY),
        new Vec2(minX, maxY)));
  }

  private static void assertIssue(WorldMap map, String code) {
    List<ValidationIssue> issues = WorldMapValidator.validate(map);
    assertTrue(issues.stream().anyMatch(issue -> issue.code().equals(code)),
        () -> "缺少校验错误 " + code + "，实际: "
            + issues.stream().map(ValidationIssue::code).toList());
  }
}
