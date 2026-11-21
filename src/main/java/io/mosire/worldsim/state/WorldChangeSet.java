package io.mosire.worldsim.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.country.RailEconomy;
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
import io.mosire.worldsim.orbat.FormationStock;

import java.util.ArrayList;
import java.util.HashSet;
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

  public WorldChange.AdvanceEvent event() {
    WorldChange.AdvanceEvent event = null;
    for (WorldChange change : changes) {
      if (!(change instanceof WorldChange.AdvanceEvent next)) continue;
      if (event != null) throw new IllegalArgumentException("同一节点只能有一个事件时刻");
      event = next;
    }
    return event;
  }

  public StaticWorldState apply(StaticWorldState base) {
    Objects.requireNonNull(base, "base");
    var country = base.countries();
    var orbat = base.formations();
    var civic = base.civics();
    var info = base.intelligence();
    var turns = base.turns();
    String time = base.simulationTime();
    List<WorldChange> mapChanges = new ArrayList<>();
    for (WorldChange change : changes) {
      switch (change) {
        case WorldChange.SetPolitics op -> civic = civic.withPolitics(op.countryId(), op.politics());
        case WorldChange.PutCensus op -> civic = civic.withCensus(op.records());
        case WorldChange.PutTreaty op -> civic = civic.withTreaty(op.treaty());
        case WorldChange.PutInfo op -> info = info.with(op.entry());
        case WorldChange.PutHeadquarters op -> orbat = orbat.withHeadquarters(op.headquarters());
        case WorldChange.PutIntelligenceDocument op -> info = info.with(op.intelligenceDocument());
        case WorldChange.PutContact op -> info = info.with(new io.mosire.worldsim.info.InfoState.ContactUpdate(op.contact().id(), op.contact()), "gm");
        case WorldChange.RetractContact op -> info = info.with(new io.mosire.worldsim.info.InfoState.ContactUpdate(op.id(), null), "gm");
        case WorldChange.OpenTurn op -> turns = turns.open(op.brief());
        case WorldChange.SubmitDecision op -> {
          var brief = turns.requireOpen(op.turnId()).brief();
          if (brief.legacy() != (op.countryId() != null)) throw new IllegalArgumentException("收件身份与回合口径不一致");
          turns = turns.submit(op.turnId(), op.participantId(), op.proposal());
        }
        case WorldChange.ResolveTurn op -> {
          turns = turns.resolve(op.turnId(), op.proposals(), op.resolution(), op.rulings());
          for (var proposal : new java.util.TreeMap<>(op.proposals()).entrySet()) {
            for (var report : proposal.getValue().reports()) info = info.with(report, proposal.getKey());
          }
        }
        case WorldChange.PutCountry op -> country = country.withCountry(op.country());
        case WorldChange.SetTerritory op -> country = country.withTerritory(op.provinceIds(), op.field(), op.assignment());
        case WorldChange.SetTransitRight op -> country = country.withTransitRight(op.grantorId(), op.guestId(), op.evidence());
        case WorldChange.PutCommandDepot op -> orbat = orbat.withDepot(op.depot());
        case WorldChange.SetManpowerPool op -> country = country.withManpower(op.countryId(), op.pool());
        case WorldChange.ConfigureRailEconomy op -> {
          if (time == null) time = op.time();
          if (!time.equals(op.time())) throw new IllegalArgumentException("铁路经济配置须对应当前剧本时刻");
          var rail = country.railEconomy();
          country = country.withRailEconomy(rail == null ? RailEconomy.start(time, op.rules()) : rail.withRules(op.rules()));
        }
        case WorldChange.DeductEquipment op ->
            orbat = FormationStock.apply(orbat, op.unitId(), op.documentId(), op.adjustments(), op.evidence());
        case WorldChange.ReinforcePersonnel op -> {
          var unit = orbat.units().get(op.unitId());
          if (unit == null || !op.countryId().equals(unit.countryId())) throw new IllegalArgumentException("补员须从单位所属国家人力池扣账");
          var pool = country.manpower().get(op.countryId());
          if (pool == null) throw new IllegalArgumentException("补员需要国家人力池");
          var evidence = Evidence.afterAdjustment(pool.evidence(), op.evidence());
          country = country.spendManpower(op.countryId(), op.poolBefore(), op.poolAfter(), op.evidence());
          orbat = FormationStock.apply(orbat, op.unitId(), op.documentId(), List.of(op.receipt()), evidence);
        }
        case WorldChange.TransferEquipment op -> {
          var depot = orbat.depots().get(op.depotId());
          var unit = orbat.units().get(op.unitId());
          if (depot == null || unit == null || !depot.countryId().equals(unit.countryId()) || unit.provinceId() == null) {
            throw new IllegalArgumentException("装备交付需要本国机关及已部署部队");
          }
          var receipt = op.receipt();
          validateDelivery(country, depot.countryId(), depot.provinceId(), unit.provinceId(), op.freight());
          if (op.freight() != null) {
            var rail = country.railEconomy();
            if (rail == null) throw new IllegalArgumentException("跨省铁路交付需要当期额度");
            country = country.withRailEconomy(rail.applyCharge(depot.countryId(), receipt.itemId(), receipt.delta(), op.freight()));
          }
          var equipment = FormationStock.updateEquipment(depot.equipment(), receipt.itemId(), receipt.name(),
              op.depotBefore(), op.depotAfter(), op.evidence());
          var issued = FormationStock.equipment(equipment, receipt.itemId()).quantity();
          var evidence = new Evidence(issued.source(), issued.status(), issued.confidence(), op.evidence().date());
          orbat = FormationStock.apply(orbat.withDepot(depot.withEquipment(equipment)), op.unitId(), op.documentId(), List.of(receipt), evidence);
        }
        case WorldChange.PutOrbatDocument op -> orbat = orbat.withDocument(op.document());
        case WorldChange.PutUnit op -> orbat = orbat.withUnit(op.unit(), time);
        case WorldChange.DeployUnit op -> orbat = orbat.deploy(op.unitId(), op.provinceId(), op.evidence(), time);
        case WorldChange.SetSimulationTime op -> {
          if (time != null && op.time().compareTo(time) < 0) throw new IllegalArgumentException("剧本时间不能倒退");
          orbat = orbat.advanceMarches(time, op.time(), country);
          time = op.time();
          country = country.advanceEconomy(time);
        }
        case WorldChange.AdvanceEvent op -> {
          if (time != null && op.time().compareTo(time) < 0) throw new IllegalArgumentException("剧本时间不能倒退");
          String treatyBoundary = civic.nextBoundary(time);
          if (treatyBoundary != null && op.time().compareTo(treatyBoundary) > 0
              || !civic.boundariesAt(time, op.time()).equals(op.treatyBoundaries())) {
            throw new IllegalArgumentException("事件推进须按时刻记录条约生效或到期");
          }
          orbat = orbat.applyArrivals(time, op.time(), op.arrivals());
          time = op.time();
          var rail = country.railEconomy();
          String boundary = rail == null ? null : rail.nextBoundary();
          if (boundary != null && time.compareTo(boundary) > 0) {
            throw new IllegalArgumentException("事件推进须按时刻结算经济期");
          }
          country = country.advanceEconomy(time);
          Long period = rail == country.railEconomy() ? null : country.railEconomy().periodIndex();
          if (!Objects.equals(period, op.railPeriod())) throw new IllegalArgumentException("事件换期记录与铁路账不一致");
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
    orbat.validateHeadquarters(country.countries().keySet());
    for (var document : info.documents().values()) {
      if (!country.countries().containsKey(document.countryId()) || !country.countries().keySet().containsAll(document.countries())) {
        throw new IllegalArgumentException("情报文档国家不存在: " + document.id());
      }
    }
    for (var contact : info.contacts().values()) {
      var province = map.provinces().get(contact.provinceId());
      if (province == null || !province.kind().equals("land")) throw new IllegalArgumentException("情报标记需要有效陆省");
    }
    return new StaticWorldState(base.id(), map, country, orbat, time, civic, info, turns);
  }

  private static void validateDelivery(CountryState countries, String countryId, String from, String to,
                                       RailEconomy.Charge charge) {
    String province = from;
    requireDeliveryAccess(countries, countryId, from);
    if (charge != null) {
      var visited = new HashSet<String>();
      visited.add(from);
      for (var leg : charge.segments()) {
        var segment = leg.segment();
        if (province.equals(segment.a())) province = segment.b();
        else if (province.equals(segment.b())) province = segment.a();
        else throw new IllegalArgumentException("交付铁路路段不连续");
        if (!visited.add(province)) throw new IllegalArgumentException("交付铁路路线不能重复经过同一省");
        requireDeliveryAccess(countries, countryId, province);
      }
    }
    if (!province.equals(to)) throw new IllegalArgumentException("交付路线须连接机关和部队驻省");
  }

  private static void requireDeliveryAccess(CountryState countries, String countryId, String province) {
    var passage = countries.entry(countryId, province);
    if (passage.blocked() || passage.status().equals("unknown")) throw new IllegalArgumentException("缺少交付通行许可: " + province);
  }

  private static WorldMap applyMap(WorldMap base, List<WorldChange> mapChanges) {
    var features = new LinkedHashMap<>(base.features());
    Map<String, Province> provinces = new LinkedHashMap<>(base.provinces());
    Map<String, State> states = new LinkedHashMap<>(base.states());
    Map<String, StrategicRegion> regions = new LinkedHashMap<>(base.strategicRegions());
    Map<String, SupplyHub> hubs = new LinkedHashMap<>(base.supplyHubs());
    Map<String, Railway> railways = new LinkedHashMap<>(base.railways());
    Map<String, EntityLink> entityLinks = new LinkedHashMap<>(base.entityLinks());
    List<Adjacency> adjacencies = new ArrayList<>(base.adjacencies());

    for (WorldChange change : mapChanges) {
      switch (change) {
        case WorldChange.SetRiverCrossing op -> {
          int index = 0;
          for (; index < adjacencies.size(); index++) {
            var edge = adjacencies.get(index);
            if (edge.permitsLandMarch() && edge.connects(op.fromProvinceId(), op.toProvinceId())) {
              adjacencies.set(index, edge.withRiverCrossing(op.crossing()));
              break;
            }
          }
          if (index == adjacencies.size()) throw new IllegalArgumentException("跨河确认需要普通陆地邻接");
        }
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
        case WorldChange.PutMapFeature op -> features.put(op.feature().id(), op.feature());
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
        provinces, states, regions, hubs, railways, entityLinks, adjacencies, features);
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
