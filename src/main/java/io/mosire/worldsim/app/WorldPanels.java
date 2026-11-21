package io.mosire.worldsim.app;

import io.mosire.worldsim.country.CivicState;
import io.mosire.worldsim.info.TurnState;
import io.mosire.worldsim.map.LandEdgeConditions;
import io.mosire.worldsim.map.SupplyHub;

import java.util.List;

final class WorldPanels {
  private WorldPanels() {}

  record Decision(String id, String displayName, String status, TurnState.Proposal proposal,
                  List<String> targetAddresses, String receiptNode, Long receivedAt, List<TurnState.Ruling> rulings) {}

  record DecisionPanel(String node, String stateHash, String subjectAddress, String openingAddress,
                       String resolvedAddress, String mode, List<Decision> decisions, String resolution,
                       String subjectName) {}

  record RoundRuling(TurnState.Ruling entry, String subjectAddress, String displayName) {}
  record RoundRulings(String node, String stateHash, String openingAddress, String resolvedAddress,
                      String resolution, List<RoundRuling> rulings) {}

  record Population(String node, String stateHash, String address, List<CivicState.Census> records) {}

  record Track(String address, String id, int level, String status, Double tonnesPerDay,
               io.mosire.worldsim.state.Evidence evidence) {}
  record Hub(String address, SupplyHub hub) {}
  record Border(String address, String name, LandEdgeConditions.River river) {}
  record Logistics(String node, String stateHash, String address, List<Track> railways, List<Hub> hubs,
                   List<Border> borders) {}
}
