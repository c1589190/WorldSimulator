package io.mosire.worldsim.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.EntityLink;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;

import java.util.List;

/** 静态地图的增量操作。首版只覆盖静态地图；后续 ORBAT/INFO/动态模块各自扩展自己的 ChangeSet。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = WorldChange.PutProvince.class, name = "putProvince"),
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
    @JsonSubTypes.Type(value = WorldChange.ReplaceAdjacencies.class, name = "replaceAdjacencies")
})
public sealed interface WorldChange permits
    WorldChange.PutProvince,
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
    WorldChange.ReplaceAdjacencies {

  record PutProvince(Province province) implements WorldChange {}

  record RemoveProvince(String provinceId) implements WorldChange {}

  record PutState(State state) implements WorldChange {}

  record RemoveState(String stateId) implements WorldChange {}

  record PutStrategicRegion(StrategicRegion region) implements WorldChange {}

  record RemoveStrategicRegion(String regionId) implements WorldChange {}

  record PutSupplyHub(SupplyHub supplyHub) implements WorldChange {}

  record RemoveSupplyHub(String supplyHubId) implements WorldChange {}

  record PutRailway(Railway railway) implements WorldChange {}

  record RemoveRailway(String railwayId) implements WorldChange {}

  record PutEntityLink(EntityLink entityLink) implements WorldChange {}

  record RemoveEntityLink(String key) implements WorldChange {}

  record ReplaceAdjacencies(List<Adjacency> adjacencies) implements WorldChange {
    public ReplaceAdjacencies {
      adjacencies = List.copyOf(adjacencies);
    }
  }
}
