package io.mosire.worldsim.state;

import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.map.WorldMapValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChangeSetTest {
  private final StaticWorldState initial = SampleWorld.create();

  @Test
  void multipleWritesKeepOrderAndLastWriteWins() {
    WorldChangeSet set = WorldChangeSet.empty()
        .append(new WorldChange.PutProvince(
            SampleWorld.renameProvince(initial.map(), SampleWorld.EAST, "East one")))
        .append(new WorldChange.PutProvince(
            SampleWorld.renameProvince(initial.map(), SampleWorld.EAST, "East two")));

    assertEquals(2, set.changes().size());
    StaticWorldState result = set.apply(initial);
    assertEquals("East two", result.map().provinces().get(SampleWorld.EAST).name());
    assertEquals("East", initial.map().provinces().get(SampleWorld.EAST).name());
    assertFalse(set.isEmpty());
  }

  @Test
  void canonicalJsonRoundTripsWithoutDerivedGetterFields() {
    WorldChangeSet set = new WorldChangeSet(List.of(
        new WorldChange.PutProvince(
            SampleWorld.renameProvince(initial.map(), SampleWorld.EAST, "East one")),
        new WorldChange.PutProvince(
            SampleWorld.renameProvince(initial.map(), SampleWorld.EAST, "East two"))));

    String json = set.canonicalJson();
    assertFalse(json.contains("\"empty\""));
    assertTrue(json.indexOf("East one") < json.indexOf("East two"));
    assertEquals(set, WorldChangeSet.fromJson(json));
    assertEquals(set.apply(initial).contentHash(), WorldChangeSet.fromJson(json).apply(initial).contentHash());
    assertEquals("{\"changes\":[]}", WorldChangeSet.emptyJson());
  }

  @Test
  void removeProvinceCleansReverseReferencesAndAdjacency() {
    WorldChangeSet set = WorldChangeSet.empty()
        .append(new WorldChange.RemoveProvince(SampleWorld.WEST));
    WorldMap map = set.apply(initial).map();

    assertEquals(List.of(SampleWorld.EAST), map.states().get(SampleWorld.STATE).provinceIds());
    assertEquals(List.of(SampleWorld.EAST), map.strategicRegions().get(SampleWorld.REGION).provinceIds());
    assertTrue(map.supplyHubs().isEmpty());
    assertTrue(map.railways().isEmpty());
    assertTrue(map.adjacencies().isEmpty());
    assertTrue(WorldMapValidator.validate(map).isEmpty());
  }

  @Test
  void removeStateClearsProvinceStateReference() {
    StaticWorldState result = WorldChangeSet.empty()
        .append(new WorldChange.RemoveState(SampleWorld.STATE))
        .apply(initial);

    assertFalse(result.map().states().containsKey(SampleWorld.STATE));
    assertEquals(null, result.map().provinces().get(SampleWorld.WEST).stateId());
    assertTrue(WorldMapValidator.validate(result.map()).stream()
        .anyMatch(issue -> issue.code().equals("LAND_WITHOUT_STATE")));
  }

  @Test
  void removeStrategicRegionClearsProvinceRegionReference() {
    StaticWorldState result = WorldChangeSet.empty()
        .append(new WorldChange.RemoveStrategicRegion(SampleWorld.REGION))
        .apply(initial);

    assertFalse(result.map().strategicRegions().containsKey(SampleWorld.REGION));
    assertEquals(null, result.map().provinces().get(SampleWorld.WEST).regionId());
    assertTrue(WorldMapValidator.validate(result.map()).stream()
        .anyMatch(issue -> issue.code().equals("PROVINCE_REGION_MISSING")));
  }

  @Test
  void railwayChangeAppliesAndRoundTrips() {
    WorldChangeSet set = WorldChangeSet.empty()
        .append(new WorldChange.PutRailway(new Railway("rail-north", 2, List.of(SampleWorld.WEST, SampleWorld.EAST))));

    StaticWorldState result = set.apply(initial);
    assertEquals(2, result.map().railways().get("rail-north").level());
    assertEquals(WorldChangeSet.fromJson(set.canonicalJson()), set);
  }

  @Test
  void replaceAdjacenciesReplacesWholeList() {
    StaticWorldState result = WorldChangeSet.empty()
        .append(new WorldChange.ReplaceAdjacencies(List.of()))
        .apply(initial);
    assertTrue(result.map().adjacencies().isEmpty());
  }
}
