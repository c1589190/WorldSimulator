package io.mosire.worldsim.orbat;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.state.Evidence;

/** Equipment held by one command for issue to formations. */
public record CommandDepot(String id, String name, String countryId, String provinceId,
                           OrbatDocument.Inventory equipment, Evidence evidence) {
  public CommandDepot {
    NodeAddress.requireSegment(id);
    NodeAddress.requireSegment(countryId);
    NodeAddress.requireSegment(provinceId);
    if (name == null || name.isBlank()) throw new IllegalArgumentException("机关库存需要名称");
    if (equipment == null) equipment = OrbatDocument.Inventory.UNKNOWN;
    Evidence.requireRecorded(evidence);
  }

  public CommandDepot withEquipment(OrbatDocument.Inventory next) {
    return new CommandDepot(id, name, countryId, provinceId, next, evidence);
  }
}
