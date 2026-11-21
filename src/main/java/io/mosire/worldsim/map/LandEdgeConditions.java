package io.mosire.worldsim.map;

import io.mosire.worldsim.state.Evidence;

import java.util.List;

/** Evidence for one directed land leg, suitable for storing with a march. */
public record LandEdgeConditions(String fromTerrain, String toTerrain, River river, List<Track> railways) {
  public LandEdgeConditions {
    railways = List.copyOf(railways);
  }

  public record River(String status, String relation, Integer widthClass, String source, String sourceHash, Evidence evidence) {}
  public record Track(String railwayId, int level, String status, Double tonnesPerDay, Evidence evidence) {}
}
