package io.mosire.worldsim.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.EntityLink;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.WorldMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个有顺序的静态地图增量。多个写操作按调用顺序 append 到同一个 ChangeSet；
 * 保存时作为一个节点整体写入，不做 undo/revision。
 */
public record WorldChangeSet(List<WorldChange> changes) {
  public WorldChangeSet {
    changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
  }

  public static WorldChangeSet empty() {
    return new WorldChangeSet(List.of());
  }

  public WorldChangeSet append(WorldChange change) {
    Objects.requireNonNull(change, "change");
    List<WorldChange> next = new ArrayList<>(changes);
    next.add(change);
    return new WorldChangeSet(next);
  }

  @JsonIgnore
  public boolean isEmpty() {
    return changes.isEmpty();
  }

  public String canonicalJson() {
    return JsonCodec.canonicalJson(this);
  }

  public static WorldChangeSet fromJson(String json) {
    return JsonCodec.fromJson(json, WorldChangeSet.class);
  }

  /** 空变更集的规范 JSON 固定为 {@code {"changes":[]}}。 */
  public static String emptyJson() {
    return empty().canonicalJson();
  }

  public StaticWorldState apply(StaticWorldState base) {
    Objects.requireNonNull(base, "base");
    Map<String, Province> provinces = new LinkedHashMap<>(base.map().provinces());
    Map<String, State> states = new LinkedHashMap<>(base.map().states());
    Map<String, StrategicRegion> regions = new LinkedHashMap<>(base.map().strategicRegions());
    Map<String, SupplyHub> hubs = new LinkedHashMap<>(base.map().supplyHubs());
    Map<String, Railway> railways = new LinkedHashMap<>(base.map().railways());
    Map<String, EntityLink> entityLinks = new LinkedHashMap<>(base.map().entityLinks());
    List<Adjacency> adjacencies = new ArrayList<>(base.map().adjacencies());

    for (WorldChange change : changes) {
      switch (change) {
        case WorldChange.PutProvince op -> provinces.put(op.province().id(), op.province());
        case WorldChange.RemoveProvince op -> {
          provinces.remove(op.provinceId());
          for (Map.Entry<String, State> entry : states.entrySet()) {
            State state = entry.getValue();
            if (state.provinceIds().contains(op.provinceId())) {
              entry.setValue(new State(state.id(), state.name(),
                  state.provinceIds().stream().filter(id -> !id.equals(op.provinceId())).toList()));
            }
          }
          for (Map.Entry<String, StrategicRegion> entry : regions.entrySet()) {
            StrategicRegion region = entry.getValue();
            if (region.provinceIds().contains(op.provinceId())) {
              entry.setValue(new StrategicRegion(region.id(), region.name(),
                  region.provinceIds().stream().filter(id -> !id.equals(op.provinceId())).toList()));
            }
          }
          List<String> removedHubIds = hubs.entrySet().stream()
              .filter(entry -> entry.getValue().provinceId().equals(op.provinceId()))
              .map(Map.Entry::getKey)
              .toList();
          List<String> removedRailwayIds = railways.entrySet().stream()
              .filter(entry -> entry.getValue().provincePath().contains(op.provinceId()))
              .map(Map.Entry::getKey)
              .toList();
          removedHubIds.forEach(id -> hubs.remove(id));
          removedRailwayIds.forEach(id -> railways.remove(id));
          removeLinksTargeting(entityLinks, "province", op.provinceId());
          removedHubIds.forEach(id -> removeLinksTargeting(entityLinks, "supplyHub", id));
          removedRailwayIds.forEach(id -> removeLinksTargeting(entityLinks, "railway", id));
          adjacencies.removeIf(adj -> adj.a().equals(op.provinceId()) || adj.b().equals(op.provinceId()));
        }
        case WorldChange.PutState op -> states.put(op.state().id(), op.state());
        case WorldChange.RemoveState op -> {
          states.remove(op.stateId());
          removeLinksTargeting(entityLinks, "state", op.stateId());
          for (Map.Entry<String, Province> entry : provinces.entrySet()) {
            Province province = entry.getValue();
            if (op.stateId().equals(province.stateId())) {
              entry.setValue(new Province(province.id(), province.name(), province.kind(), province.terrain(),
                  province.shape(), province.displayPoint(), null, province.regionId(), province.tags()));
            }
          }
        }
        case WorldChange.PutStrategicRegion op -> regions.put(op.region().id(), op.region());
        case WorldChange.RemoveStrategicRegion op -> {
          regions.remove(op.regionId());
          removeLinksTargeting(entityLinks, "region", op.regionId());
          for (Map.Entry<String, Province> entry : provinces.entrySet()) {
            Province province = entry.getValue();
            if (op.regionId().equals(province.regionId())) {
              entry.setValue(new Province(province.id(), province.name(), province.kind(), province.terrain(),
                  province.shape(), province.displayPoint(), province.stateId(), null, province.tags()));
            }
          }
        }
        case WorldChange.PutSupplyHub op -> hubs.put(op.supplyHub().id(), op.supplyHub());
        case WorldChange.RemoveSupplyHub op -> {
          hubs.remove(op.supplyHubId());
          removeLinksTargeting(entityLinks, "supplyHub", op.supplyHubId());
        }
        case WorldChange.PutRailway op -> railways.put(op.railway().id(), op.railway());
        case WorldChange.RemoveRailway op -> {
          railways.remove(op.railwayId());
          removeLinksTargeting(entityLinks, "railway", op.railwayId());
        }
        case WorldChange.PutEntityLink op -> entityLinks.put(op.entityLink().key(), op.entityLink());
        case WorldChange.RemoveEntityLink op -> entityLinks.remove(op.key());
        case WorldChange.ReplaceAdjacencies op -> {
          adjacencies.clear();
          adjacencies.addAll(op.adjacencies());
        }
      }
    }
    WorldMap next = new WorldMap(base.map().id(), base.map().name(), base.map().width(), base.map().height(),
        provinces, states, regions, hubs, railways, entityLinks, adjacencies);
    return base.withMap(next);
  }

  private static void removeLinksTargeting(Map<String, EntityLink> links, String category, String targetId) {
    links.entrySet().removeIf(entry -> entry.getValue().category().equals(category)
        && entry.getValue().targetId().equals(targetId));
  }
}
