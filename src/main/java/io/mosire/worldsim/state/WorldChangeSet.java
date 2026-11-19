package io.mosire.worldsim.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.EntityLink;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Vec2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个有顺序的世界增量。多个写操作按调用顺序 append 到同一个 ChangeSet；
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
    var country = base.countries();
    var orbat = base.formations();
    String time = base.simulationTime();
    List<WorldChange> mapChanges = new ArrayList<>();
    for (WorldChange change : changes) {
      switch (change) {
        case WorldChange.PutCountry op -> country = country.withCountry(op.country());
        case WorldChange.SetTerritory op -> country = country.withTerritory(op.provinceIds(), op.field(), op.assignment());
        case WorldChange.SetTransitRight op -> country = country.withTransitRight(op.grantorId(), op.guestId(), op.evidence());
        case WorldChange.PutOrbatDocument op -> orbat = orbat.withDocument(op.document());
        case WorldChange.PutUnit op -> orbat = orbat.withUnit(op.unit(), time);
        case WorldChange.DeployUnit op -> orbat = orbat.deploy(op.unitId(), op.provinceId(), op.evidence(), time);
        case WorldChange.SetSimulationTime op -> {
          if (time != null && op.time().compareTo(time) < 0) throw new IllegalArgumentException("剧本时间不能倒退");
          orbat = orbat.advanceMarches(time, op.time(), country);
          time = op.time();
        }
        case WorldChange.StartMarch op -> {
          if (time == null) time = op.march().startedAt();
          orbat = orbat.startMarch(op.unitId(), op.march(), time);
        }
        case WorldChange.CancelMarch op -> orbat = orbat.cancelMarch(op.unitId(), time);
        case WorldChange.ResolveBattle op -> {
          var battle = op.battle();
          if (time == null) time = battle.endedAt();
          orbat = orbat.resolveBattle(battle, time);
          if (battle.control() != null) {
            country = country.withTerritory(List.of(battle.provinceId()), "controller",
                new CountryState.Assignment(battle.control().countryId(), battle.evidence()));
          }
        }
        default -> mapChanges.add(change);
      }
    }
    WorldMap map = mapChanges.isEmpty() ? base.map() : applyMap(base.map(), mapChanges);
    return new StaticWorldState(base.id(), map, country, orbat, time);
  }

  private static WorldMap applyMap(WorldMap base, List<WorldChange> mapChanges) {
    Map<String, Province> provinces = new LinkedHashMap<>(base.provinces());
    Map<String, State> states = new LinkedHashMap<>(base.states());
    Map<String, StrategicRegion> regions = new LinkedHashMap<>(base.strategicRegions());
    Map<String, SupplyHub> hubs = new LinkedHashMap<>(base.supplyHubs());
    Map<String, Railway> railways = new LinkedHashMap<>(base.railways());
    Map<String, EntityLink> entityLinks = new LinkedHashMap<>(base.entityLinks());
    List<Adjacency> adjacencies = new ArrayList<>(base.adjacencies());

    for (WorldChange change : mapChanges) {
      switch (change) {
        case WorldChange.PutProvince op -> provinces.put(op.province().id(), op.province());
        case WorldChange.PatchProvince op -> {
          Province old = provinces.get(op.provinceId());
          if (old == null) throw new IllegalArgumentException("省不存在: " + op.provinceId());
          Map<String, JsonNode> fields = op.fields();
          ProvinceShape shape = fields.containsKey("shape")
              ? JsonCodec.mapper().convertValue(fields.get("shape"), ProvinceShape.class) : old.shape();
          Vec2 point = fields.containsKey("displayPoint")
              ? JsonCodec.mapper().convertValue(fields.get("displayPoint"), Vec2.class) : old.displayPoint();
          List<String> tags = fields.containsKey("tags")
              ? JsonCodec.mapper().convertValue(fields.get("tags"), new TypeReference<List<String>>() {}) : old.tags();
          provinces.put(op.provinceId(), new Province(old.id(), patchText(fields, "name", old.name()),
              patchText(fields, "kind", old.kind()), patchText(fields, "terrain", old.terrain()), shape, point,
              patchText(fields, "stateId", old.stateId()), patchText(fields, "regionId", old.regionId()), tags));
        }
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
        default -> throw new IllegalArgumentException("不是地图变更: " + change);
      }
    }
    return new WorldMap(base.id(), base.name(), base.width(), base.height(),
        provinces, states, regions, hubs, railways, entityLinks, adjacencies);
  }

  private static void removeLinksTargeting(Map<String, EntityLink> links, String category, String targetId) {
    links.entrySet().removeIf(entry -> entry.getValue().category().equals(category)
        && entry.getValue().targetId().equals(targetId));
  }

  private static String patchText(Map<String, JsonNode> fields, String key, String old) {
    JsonNode value = fields.get(key);
    if (value == null) return old;
    if (value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException("省字段必须为字符串: " + key);
    return value.textValue();
  }
}
