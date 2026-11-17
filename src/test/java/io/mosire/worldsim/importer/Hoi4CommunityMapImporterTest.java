package io.mosire.worldsim.importer;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.WorldMapValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

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
    assertEquals(1, map.adjacencies().size());
    assertEquals(1, map.supplyHubs().size());
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

    assertEquals(2, result.stats().usedProvinces());
    assertEquals(2, result.stats().states());
    assertEquals(1, result.stats().regions());
    assertEquals(1, result.stats().adjacencies());
    assertEquals(1, result.stats().supplyHubs());
    assertTrue(result.warnings().isEmpty(), () -> result.warnings().toString());
  }

}
