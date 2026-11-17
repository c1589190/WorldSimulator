package io.mosire.worldsim.cli;

import io.mosire.worldsim.map.WorldMapValidator;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.SqliteTimelineStore;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.timeline.TimelineWorkingCopy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticWorldTest {
  @Test
  @Timeout(30)
  void threeHundredProvinceGridIsValidAndReplayable() {
    StaticWorldState state = SyntheticWorld.create(20, 15);

    assertEquals(300, state.map().provinces().size());
    assertEquals(15, state.map().states().size());
    assertEquals(5, state.map().strategicRegions().size());
    assertEquals(565, state.map().adjacencies().size());
    assertTrue(WorldMapValidator.validate(state.map()).isEmpty());

    try (SqliteTimelineStore store = SqliteTimelineStore.inMemory()) {
      TimelineService service = new TimelineService(store);
      TimelineNode genesis = service.createGenesis(state, "scale-test");
      TimelineWorkingCopy workingCopy = service.openNext(genesis.ref());
      service.apply(workingCopy, new WorldChange.PutProvince(
          SampleWorld.renameProvince(state.map(), "p-0-0", "Changed")));
      TimelineNode main1 = service.save(workingCopy, 1, "scale-test");

      assertEquals("Changed", service.load(main1.ref()).map().provinces().get("p-0-0").name());
      assertEquals(2, service.verifyAll());
    }
  }

  @Test
  void oneProvinceWorldHasNoAdjacency() {
    StaticWorldState state = SyntheticWorld.create(1, 1);
    assertTrue(state.map().adjacencies().isEmpty());
    assertTrue(WorldMapValidator.validate(state.map()).isEmpty());
  }

  @Test
  void rejectsImpossibleSpecification() {
    assertThrows(IllegalArgumentException.class, () -> SyntheticWorld.create(0, 1));
    assertThrows(IllegalArgumentException.class, () -> SyntheticWorld.create(1, 0));
    assertThrows(IllegalArgumentException.class, () -> SyntheticWorld.create(1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> SyntheticWorld.create(501, 1));
  }
}
