package io.mosire.worldsim.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.EntityLink;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

/** Persisted operations across the world domains. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = WorldChange.PutProvince.class, name = "putProvince"),
    @JsonSubTypes.Type(value = WorldChange.PatchProvince.class, name = "patchProvince"),
    @JsonSubTypes.Type(value = WorldChange.RemoveProvince.class, name = "removeProvince"),
    @JsonSubTypes.Type(value = WorldChange.PutState.class, name = "putState"),
    @JsonSubTypes.Type(value = WorldChange.RemoveState.class, name = "removeState"),
    @JsonSubTypes.Type(value = WorldChange.PutStrategicRegion.class, name = "putStrategicRegion"),
    @JsonSubTypes.Type(value = WorldChange.RemoveStrategicRegion.class, name = "removeStrategicRegion"),
    @JsonSubTypes.Type(value = WorldChange.PutSupplyHub.class, name = "putSupplyHub"),
    @JsonSubTypes.Type(value = WorldChange.RemoveSupplyHub.class, name = "removeSupplyHub"),
    @JsonSubTypes.Type(value = WorldChange.PutRailway.class, name = "putRailway"),
    @JsonSubTypes.Type(value = WorldChange.RemoveRailway.class, name = "removeRailway"),
    @JsonSubTypes.Type(value = WorldChange.PutEntityLink.class, name = "putEntityLink"),
    @JsonSubTypes.Type(value = WorldChange.RemoveEntityLink.class, name = "removeEntityLink"),
    @JsonSubTypes.Type(value = WorldChange.ReplaceAdjacencies.class, name = "replaceAdjacencies"),
    @JsonSubTypes.Type(value = WorldChange.PutCountry.class, name = "putCountry"),
    @JsonSubTypes.Type(value = WorldChange.SetTerritory.class, name = "setTerritory"),
    @JsonSubTypes.Type(value = WorldChange.PutOrbatDocument.class, name = "putOrbatDocument"),
    @JsonSubTypes.Type(value = WorldChange.PutUnit.class, name = "putUnit"),
    @JsonSubTypes.Type(value = WorldChange.DeployUnit.class, name = "deployUnit")
})
public sealed interface WorldChange permits
    WorldChange.PutProvince,
    WorldChange.PatchProvince,
    WorldChange.RemoveProvince,
    WorldChange.PutState,
    WorldChange.RemoveState,
    WorldChange.PutStrategicRegion,
    WorldChange.RemoveStrategicRegion,
    WorldChange.PutSupplyHub,
    WorldChange.RemoveSupplyHub,
    WorldChange.PutRailway,
    WorldChange.RemoveRailway,
    WorldChange.PutEntityLink,
    WorldChange.RemoveEntityLink,
    WorldChange.ReplaceAdjacencies,
    WorldChange.PutCountry,
    WorldChange.SetTerritory,
    WorldChange.PutOrbatDocument,
    WorldChange.PutUnit,
    WorldChange.DeployUnit {

  record PutProvince(Province province) implements WorldChange {
    public PutProvince { requireValue(province, "province"); }
  }

  /** Explicit fields only; a null JSON value can clear optional references. */
  record PatchProvince(String provinceId, Map<String, JsonNode> fields) implements WorldChange {
    public PatchProvince {
      if (provinceId == null || provinceId.isBlank() || fields == null || fields.isEmpty()) {
        throw new IllegalArgumentException("patchProvince 需要省 ID 与修改字段");
      }
      Set<String> allowed = Set.of("name", "kind", "terrain", "shape", "displayPoint", "stateId", "regionId", "tags");
      Map<String, JsonNode> copy = new LinkedHashMap<>();
      fields.forEach((key, value) -> {
        if (!allowed.contains(key) || value == null) throw new IllegalArgumentException("无效省字段: " + key);
        copy.put(key, value.deepCopy());
      });
      fields = Collections.unmodifiableMap(copy);
    }

    @Override public Map<String, JsonNode> fields() {
      Map<String, JsonNode> copy = new LinkedHashMap<>();
      fields.forEach((key, value) -> copy.put(key, value.deepCopy()));
      return Collections.unmodifiableMap(copy);
    }
  }

  record RemoveProvince(String provinceId) implements WorldChange {
    public RemoveProvince { requireId(provinceId, "provinceId"); }
  }

  record PutState(State state) implements WorldChange {
    public PutState { requireValue(state, "state"); }
  }

  record RemoveState(String stateId) implements WorldChange {
    public RemoveState { requireId(stateId, "stateId"); }
  }

  record PutStrategicRegion(StrategicRegion region) implements WorldChange {
    public PutStrategicRegion { requireValue(region, "region"); }
  }

  record RemoveStrategicRegion(String regionId) implements WorldChange {
    public RemoveStrategicRegion { requireId(regionId, "regionId"); }
  }

  record PutSupplyHub(SupplyHub supplyHub) implements WorldChange {
    public PutSupplyHub { requireValue(supplyHub, "supplyHub"); }
  }

  record RemoveSupplyHub(String supplyHubId) implements WorldChange {
    public RemoveSupplyHub { requireId(supplyHubId, "supplyHubId"); }
  }

  record PutRailway(Railway railway) implements WorldChange {
    public PutRailway { requireValue(railway, "railway"); }
  }

  record RemoveRailway(String railwayId) implements WorldChange {
    public RemoveRailway { requireId(railwayId, "railwayId"); }
  }

  record PutEntityLink(EntityLink entityLink) implements WorldChange {
    public PutEntityLink { requireValue(entityLink, "entityLink"); }
  }

  record RemoveEntityLink(String key) implements WorldChange {
    public RemoveEntityLink { requireId(key, "key"); }
  }

  record ReplaceAdjacencies(List<Adjacency> adjacencies) implements WorldChange {
    public ReplaceAdjacencies {
      requireValue(adjacencies, "adjacencies");
      adjacencies = List.copyOf(adjacencies);
    }
  }

  record PutCountry(CountryState.Country country) implements WorldChange {
    public PutCountry { requireValue(country, "country"); }
  }

  record SetTerritory(List<String> provinceIds, String field, CountryState.Assignment assignment)
      implements WorldChange {
    public SetTerritory {
      requireValue(provinceIds, "provinceIds");
      provinceIds = List.copyOf(provinceIds);
      if (provinceIds.isEmpty() || new HashSet<>(provinceIds).size() != provinceIds.size()) {
        throw new IllegalArgumentException("归属修改需要不重复的省 ID");
      }
      provinceIds.forEach(NodeAddress::requireSegment);
      if (!"owner".equals(field) && !"controller".equals(field)) {
        throw new IllegalArgumentException("归属字段须为 owner 或 controller");
      }
    }
  }

  record PutOrbatDocument(OrbatDocument document) implements WorldChange {
    public PutOrbatDocument { requireValue(document, "document"); }
  }

  record PutUnit(OrbatState.Unit unit) implements WorldChange {
    public PutUnit { requireValue(unit, "unit"); }
  }

  record DeployUnit(String unitId, String provinceId, Evidence evidence) implements WorldChange {
    public DeployUnit {
      NodeAddress.requireSegment(unitId);
      if (provinceId != null) NodeAddress.requireSegment(provinceId);
      requireValue(evidence, "evidence");
    }
  }

  private static void requireId(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不得为空白");
  }

  private static void requireValue(Object value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " 不得为空");
  }
}
