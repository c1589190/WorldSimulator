package io.mosire.worldsim.importer;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.WorldMapValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hoi4CommunityMapImporterTest {
  @Test
  void importsTinyHoi4MapWithPolygonsStatesRegionsAndSupply(@TempDir Path tempDir) throws IOException {
    Path source = Hoi4FixtureFiles.write(tempDir.resolve("community-map"));

    var result = new Hoi4CommunityMapImporter().importWorld(source);
    var state = result.state();
    var map = state.map();

    assertEquals(4, map.width());
    assertEquals(2, map.height());
    assertEquals(2, map.provinces().size());
    assertEquals(2, map.states().size());
    assertEquals(1, map.strategicRegions().size());
    assertEquals(2, map.adjacencies().size());
    assertEquals("special-sea", map.adjacencies().get(1).kind());
    assertEquals("1", map.adjacencies().get(1).through());
    assertEquals(1, map.supplyHubs().size());
    assertEquals(1, map.railways().size());
    assertEquals("1", map.railways().get("railway-0001").provincePath().get(0));
    assertEquals(7, map.entityLinks().size());
    assertEquals("province", map.entityLinks().get("province:1").category());
    assertEquals("railway", map.entityLinks().get("railway:railway-0001").category());
    assertTrue(WorldMapValidator.validate(map).isEmpty());

    var p1 = map.provinces().get("1");
    var p2 = map.provinces().get("2");
    assertEquals("Alpha Province", p1.name());
    assertEquals("plains", p1.terrain());
    assertEquals("1", p1.stateId());
    assertEquals("10", p1.regionId());
    assertEquals("Beta Province", p2.name());
    assertEquals("forest", p2.terrain());
    assertEquals("2", p2.stateId());
    assertEquals("10", p2.regionId());

    assertEquals("Alpha", map.states().get("1").name());
    assertEquals("Beta", map.states().get("2").name());
    assertEquals("Core", map.strategicRegions().get("10").name());
    assertEquals("supply-1", map.supplyHubs().get("supply-1").id());

    var resolved = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-0/map/province/1"));
    assertEquals("province:1", resolved.stableId());

    assertEquals("community-map", result.state().id());
    assertEquals(2, result.stats().usedProvinces());
    assertEquals(2, result.stats().states());
    assertEquals(1, result.stats().regions());
    assertEquals(2, result.stats().adjacencies());
    assertEquals(1, result.stats().supplyHubs());
    assertEquals(1, result.stats().railways());
    assertEquals(7, result.stats().entityLinks());
    assertEquals(0, result.stats().inferredSeaProvinces());
    assertTrue(result.warnings().isEmpty(), () -> result.warnings().toString());
  }

  @Test
  void salvagesProvincesOutsideMalformedStateBlock(@TempDir Path tempDir) throws IOException {
    Path source = Hoi4FixtureFiles.writeWithProvincesOutsideStateBlock(
        tempDir.resolve("malformed-state-map"));

    var result = new Hoi4CommunityMapImporter().importWorld(source);

    assertEquals(2, result.state().map().provinces().size());
    assertEquals("2", result.state().map().provinces().get("2").stateId());
    assertTrue(WorldMapValidator.validate(result.state().map()).isEmpty());
  }

  @Test
  void infersSeaFromHeightmapAndDropsOceanState(@TempDir Path tempDir) throws IOException {
    Path source = Hoi4FixtureFiles.writeWithWaterProvince(tempDir.resolve("water-map"));

    var result = new Hoi4CommunityMapImporter().importWorld(source);
    var map = result.state().map();

    assertEquals(2, map.provinces().size());
    assertEquals("land", map.provinces().get("1").kind());
    assertEquals("1", map.provinces().get("1").stateId());
    assertEquals("sea", map.provinces().get("2").kind());
    assertEquals(null, map.provinces().get("2").stateId());
    assertEquals(1, map.states().size());
    assertEquals(1, map.supplyHubs().size());
    assertEquals("sea", map.adjacencies().get(0).kind());
    assertEquals(1, result.stats().inferredSeaProvinces());
    assertEquals(List.of(2), result.inferredSeaProvinceIds());
    assertTrue(WorldMapValidator.validate(map).isEmpty());
    assertTrue(result.warnings().stream()
        .anyMatch(warning -> warning.contains("heightmap 推断 1")));
  }

}
