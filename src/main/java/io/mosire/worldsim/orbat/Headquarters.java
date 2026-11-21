package io.mosire.worldsim.orbat;

import io.mosire.worldsim.address.NodeAddress;
import java.util.List;

public record Headquarters(String id, String name, String kind, String countryId, String parentId,
                           String depotId, List<String> unitIds) {
  public Headquarters {
    NodeAddress.requireSegment(id);
    NodeAddress.requireSegment(countryId);
    if (name == null || name.isBlank() || !("general-staff".equals(kind) || "army-staff".equals(kind))
        || "general-staff".equals(kind) && parentId != null) throw new IllegalArgumentException("参谋机关字段无效");
    if (parentId != null) NodeAddress.requireSegment(parentId);
    if (depotId != null) NodeAddress.requireSegment(depotId);
    unitIds = unitIds == null ? List.of() : unitIds.stream().distinct().sorted().toList();
    unitIds.forEach(NodeAddress::requireSegment);
  }
}
