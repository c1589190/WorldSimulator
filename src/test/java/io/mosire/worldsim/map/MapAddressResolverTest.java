package io.mosire.worldsim.map;

import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.cli.SampleWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapAddressResolverTest {
  private final WorldMap map = SampleWorld.createMap();

  @Test
  void resolvesProvinceByStrictAddress() {
    var resolved = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-360/map/province/east"));

    assertEquals("province", resolved.kind());
    assertEquals("province:east", resolved.stableId());
    assertEquals("East", resolved.displayName());
    Province province = assertInstanceOf(Province.class, resolved.value());
    assertEquals(SampleWorld.EAST, province.id());
    assertEquals(SampleWorld.STATE, province.stateId());
  }

  @Test
  void resolvesStateRegionAndSupplyHub() {
    var state = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-360/map/state/heartland"));
    assertEquals("state:heartland", state.stableId());
    assertEquals("Heartland", state.displayName());
    assertInstanceOf(State.class, state.value());

    var region = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-360/map/region/core"));
    assertEquals("region:core", region.stableId());
    assertEquals("Core", region.displayName());
    assertInstanceOf(StrategicRegion.class, region.value());

    var hub = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-360/map/supplyHub/depot-west"));
    assertEquals("supplyHub:depot-west", hub.stableId());
    assertEquals("depot-west", hub.displayName());
    assertInstanceOf(SupplyHub.class, hub.value());

    var railway = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-360/map/railway/rail-main"));
    assertEquals("railway:rail-main", railway.stableId());
    assertEquals("rail-main", railway.displayName());
    assertInstanceOf(Railway.class, railway.value());

    var link = MapAddressResolver.resolve(map,
        NodeAddress.parse("tree1-360/map/link/province:west"));
    assertEquals("link:province:west", link.stableId());
    assertInstanceOf(EntityLink.class, link.value());
  }

  @Test
  void rejectsMissingUnknownAndTrailingEntityPaths() {
    assertCode("ADDRESS_ENTITY_PATH_MISSING",
        () -> MapAddressResolver.resolve(map, NodeAddress.parse("tree1-360")));
    assertCode("MAP_ENTITY_TYPE_MISSING",
        () -> MapAddressResolver.resolve(map, NodeAddress.parse("tree1-360/map")));
    assertCode("MAP_ENTITY_UNKNOWN_TYPE",
        () -> MapAddressResolver.resolve(map, NodeAddress.parse("tree1-360/map/unit/x")));
    assertCode("MAP_ENTITY_ID_MISSING",
        () -> MapAddressResolver.resolve(map, NodeAddress.parse("tree1-360/map/province")));
    assertCode("MAP_ENTITY_PATH_TRAILING",
        () -> MapAddressResolver.resolve(map, NodeAddress.parse("tree1-360/map/province/east/extra")));
    assertCode("MAP_ADDRESS_UNSUPPORTED",
        () -> MapAddressResolver.resolve(map, NodeAddress.parse("tree1-360/unit/army/x")));
  }

  @Test
  void rejectsMissingEntitiesWithoutInventingFallbacks() {
    AddressException error = assertThrows(AddressException.class,
        () -> MapAddressResolver.resolve(map,
            NodeAddress.parse("tree1-360/map/province/missing")));
    assertEquals("MAP_ENTITY_NOT_FOUND", error.code());
    assertTrue(error.getMessage().contains("province:missing") || error.getMessage().contains("id=missing"));
  }

  private static void assertCode(String code, Runnable operation) {
    AddressException error = assertThrows(AddressException.class, operation::run);
    assertEquals(code, error.code(), error.getMessage());
  }
}
