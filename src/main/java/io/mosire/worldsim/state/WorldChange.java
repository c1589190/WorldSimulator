package io.mosire.worldsim.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.country.CivicState;
import io.mosire.worldsim.info.InfoState;
import io.mosire.worldsim.info.TurnState;
import io.mosire.worldsim.country.RailEconomy;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.EntityLink;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.MapFeature;
import io.mosire.worldsim.orbat.Headquarters;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.orbat.OrbatDocument;
import io.mosire.worldsim.orbat.OrbatState;
import io.mosire.worldsim.orbat.March;
import io.mosire.worldsim.orbat.Battle;
import io.mosire.worldsim.orbat.CommandDepot;
import io.mosire.worldsim.orbat.FormationStock;
import io.mosire.worldsim.timeline.SimulationTime;

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
    @JsonSubTypes.Type(value = WorldChange.SetRiverCrossing.class, name = "setRiverCrossing"),
    @JsonSubTypes.Type(value = WorldChange.PutCountry.class, name = "putCountry"),
    @JsonSubTypes.Type(value = WorldChange.SetTerritory.class, name = "setTerritory"),
    @JsonSubTypes.Type(value = WorldChange.PutOrbatDocument.class, name = "putOrbatDocument"),
    @JsonSubTypes.Type(value = WorldChange.PutUnit.class, name = "putUnit"),
    @JsonSubTypes.Type(value = WorldChange.DeployUnit.class, name = "deployUnit"),
    @JsonSubTypes.Type(value = WorldChange.SetSimulationTime.class, name = "setSimulationTime"),
    @JsonSubTypes.Type(value = WorldChange.AdvanceEvent.class, name = "advanceEvent"),
    @JsonSubTypes.Type(value = WorldChange.StartMarch.class, name = "startMarch"),
    @JsonSubTypes.Type(value = WorldChange.CancelMarch.class, name = "cancelMarch"),
    @JsonSubTypes.Type(value = WorldChange.ResolveBattle.class, name = "resolveBattle"),
    @JsonSubTypes.Type(value = WorldChange.SetTransitRight.class, name = "setTransitRight"),
    @JsonSubTypes.Type(value = WorldChange.PutCommandDepot.class, name = "putCommandDepot"),
    @JsonSubTypes.Type(value = WorldChange.SetManpowerPool.class, name = "setManpowerPool"),
    @JsonSubTypes.Type(value = WorldChange.ConfigureRailEconomy.class, name = "configureRailEconomy"),
    @JsonSubTypes.Type(value = WorldChange.DeductEquipment.class, name = "deductEquipment"),
    @JsonSubTypes.Type(value = WorldChange.TransferEquipment.class, name = "transferEquipment"),
    @JsonSubTypes.Type(value = WorldChange.ReinforcePersonnel.class, name = "reinforcePersonnel"),
    @JsonSubTypes.Type(value = WorldChange.SetPolitics.class, name = "setPolitics"),
    @JsonSubTypes.Type(value = WorldChange.PutCensus.class, name = "putCensus"),
    @JsonSubTypes.Type(value = WorldChange.PutTreaty.class, name = "putTreaty"),
    @JsonSubTypes.Type(value = WorldChange.PutMapFeature.class, name = "putMapFeature"),
    @JsonSubTypes.Type(value = WorldChange.PutHeadquarters.class, name = "putHeadquarters"),
    @JsonSubTypes.Type(value = WorldChange.PutIntelligenceDocument.class, name = "putIntelligenceDocument"),
    @JsonSubTypes.Type(value = WorldChange.PutContact.class, name = "putContact"),
    @JsonSubTypes.Type(value = WorldChange.RetractContact.class, name = "retractContact"),
    @JsonSubTypes.Type(value = WorldChange.PutInfo.class, name = "putInfo"),
    @JsonSubTypes.Type(value = WorldChange.OpenTurn.class, name = "openTurn"),
    @JsonSubTypes.Type(value = WorldChange.SubmitDecision.class, name = "submitDecision"),
    @JsonSubTypes.Type(value = WorldChange.ResolveTurn.class, name = "resolveTurn")
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
    WorldChange.SetRiverCrossing,
    WorldChange.PutCountry,
    WorldChange.SetTerritory,
    WorldChange.PutOrbatDocument,
    WorldChange.PutUnit,
    WorldChange.DeployUnit,
    WorldChange.SetSimulationTime,
    WorldChange.AdvanceEvent,
    WorldChange.StartMarch,
    WorldChange.CancelMarch,
    WorldChange.ResolveBattle,
    WorldChange.SetTransitRight,
    WorldChange.EconomyChange, WorldChange.SetPolitics, WorldChange.PutCensus, WorldChange.PutTreaty,
    WorldChange.PutMapFeature, WorldChange.PutHeadquarters, WorldChange.PutIntelligenceDocument,
    WorldChange.PutContact, WorldChange.RetractContact, WorldChange.PutInfo, WorldChange.OpenTurn, WorldChange.SubmitDecision, WorldChange.ResolveTurn {

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

  record SetRiverCrossing(String fromProvinceId, String toProvinceId, Adjacency.RiverCrossing crossing) implements WorldChange {
    public SetRiverCrossing {
      NodeAddress.requireSegment(fromProvinceId);
      NodeAddress.requireSegment(toProvinceId);
      if (fromProvinceId.equals(toProvinceId)) throw new IllegalArgumentException("跨河邻接需要两个不同的省");
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

  record SetSimulationTime(String time) implements WorldChange {
    public SetSimulationTime { time = SimulationTime.normalize(time); }
  }

  record AdvanceEvent(String time, List<March.Arrival> arrivals, Long railPeriod,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CivicState.Boundary> treatyBoundaries) implements WorldChange {
    public AdvanceEvent(String time, List<March.Arrival> arrivals) { this(time, arrivals, null, List.of()); }
    public AdvanceEvent(String time, List<March.Arrival> arrivals, Long railPeriod) { this(time, arrivals, railPeriod, List.of()); }

    public AdvanceEvent {
      time = SimulationTime.normalize(time);
      arrivals = List.copyOf(arrivals);
      treatyBoundaries = treatyBoundaries == null ? List.of() : List.copyOf(treatyBoundaries);
    }
  }

  record StartMarch(String unitId, March march) implements WorldChange {
    public StartMarch {
      NodeAddress.requireSegment(unitId);
      requireValue(march, "march");
    }
  }

  record CancelMarch(String unitId) implements WorldChange {
    public CancelMarch { NodeAddress.requireSegment(unitId); }
  }

  record ResolveBattle(Battle battle) implements WorldChange {
    public ResolveBattle { requireValue(battle, "battle"); }
  }

  record SetTransitRight(String grantorId, String guestId, Evidence evidence) implements WorldChange {
    public SetTransitRight {
      NodeAddress.requireSegment(grantorId);
      NodeAddress.requireSegment(guestId);
    }
  }

  sealed interface EconomyChange extends WorldChange permits PutCommandDepot, SetManpowerPool,
      ConfigureRailEconomy, DeductEquipment, TransferEquipment, ReinforcePersonnel {}

  record PutCommandDepot(CommandDepot depot) implements EconomyChange {
    public PutCommandDepot { requireValue(depot, "depot"); }
  }

  record SetManpowerPool(String countryId, CountryState.Manpower pool) implements EconomyChange {
    public SetManpowerPool {
      NodeAddress.requireSegment(countryId);
      requireValue(pool, "pool");
    }
  }

  record ConfigureRailEconomy(String time, RailEconomy.Rules rules) implements EconomyChange {
    public ConfigureRailEconomy {
      time = SimulationTime.normalize(time);
      requireValue(rules, "rules");
    }
  }

  record DeductEquipment(String unitId, String documentId, String itemId, List<FormationStock.Change> adjustments,
                         Evidence evidence) implements EconomyChange {
    public DeductEquipment {
      NodeAddress.requireSegment(unitId);
      NodeAddress.requireSegment(documentId);
      NodeAddress.requireSegment(itemId);
      Evidence.requireRecorded(evidence);
      if (adjustments == null || adjustments.isEmpty()) throw new IllegalArgumentException("扣装需要至少一项实编变更");
      adjustments = List.copyOf(adjustments);
      if (adjustments.stream().anyMatch(change -> !itemId.equals(change.itemId()) || change.delta() >= 0)) {
        throw new IllegalArgumentException("扣装回执包含不匹配的装备变更");
      }
    }
  }

  record TransferEquipment(String depotId, String unitId, String documentId, FormationStock.Change receipt,
      int depotBefore, int depotAfter, RailEconomy.Charge freight, Evidence evidence) implements EconomyChange {
    public TransferEquipment {
      NodeAddress.requireSegment(depotId);
      NodeAddress.requireSegment(unitId);
      NodeAddress.requireSegment(documentId);
      Evidence.requireRecorded(evidence);
      if (receipt == null || receipt.itemId() == null || receipt.delta() <= 0 || depotAfter < 0
          || depotBefore <= depotAfter || depotBefore - depotAfter != receipt.delta()) {
        throw new IllegalArgumentException("机关出库与实编入账数量不一致");
      }
    }
  }

  record ReinforcePersonnel(String countryId, String unitId, String documentId, FormationStock.Change receipt,
      int poolBefore, int poolAfter, Evidence evidence) implements EconomyChange {
    public ReinforcePersonnel {
      NodeAddress.requireSegment(countryId);
      NodeAddress.requireSegment(unitId);
      NodeAddress.requireSegment(documentId);
      Evidence.requireRecorded(evidence);
      if (receipt == null || receipt.itemId() != null || receipt.delta() <= 0 || poolAfter < 0
          || poolBefore <= poolAfter || poolBefore - poolAfter != receipt.delta()) {
        throw new IllegalArgumentException("人力池扣减与实编补员数量不一致");
      }
    }
  }

  record SetPolitics(String countryId, CivicState.Politics politics) implements WorldChange {
    public SetPolitics {
      NodeAddress.requireSegment(countryId);
      requireValue(politics, "politics");
    }
  }

  record PutCensus(List<CivicState.Census> records) implements WorldChange {
    public PutCensus {
      if (records == null || records.isEmpty()) throw new IllegalArgumentException("缺少人口记录");
      records = List.copyOf(records);
    }
  }

  record PutTreaty(CivicState.Treaty treaty) implements WorldChange {
    public PutTreaty { requireValue(treaty, "treaty"); }
  }

  record PutMapFeature(MapFeature feature) implements WorldChange {
    public PutMapFeature { requireValue(feature, "feature"); }
  }

  record PutHeadquarters(Headquarters headquarters) implements WorldChange {
    public PutHeadquarters { requireValue(headquarters, "headquarters"); }
  }

  record PutIntelligenceDocument(InfoState.Document intelligenceDocument) implements WorldChange {
    public PutIntelligenceDocument { requireValue(intelligenceDocument, "intelligenceDocument"); }
  }

  record PutContact(InfoState.Contact contact) implements WorldChange {
    public PutContact {
      requireValue(contact, "contact");
      if (!"gm".equals(contact.owner()) || contact.id().startsWith("dm-")) throw new IllegalArgumentException("GM 情报须归属 gm");
    }
  }

  record RetractContact(String id) implements WorldChange {
    public RetractContact { NodeAddress.requireSegment(id); }
  }

  record PutInfo(InfoState.Entry entry) implements WorldChange {
    public PutInfo { requireValue(entry, "entry"); }
  }

  record OpenTurn(TurnState.Brief brief) implements WorldChange {
    public OpenTurn { requireValue(brief, "brief"); }
  }

  record SubmitDecision(String turnId, String countryId, TurnState.Proposal proposal, String decisionMakerId)
      implements WorldChange {
    public SubmitDecision(String turnId, String countryId, TurnState.Proposal proposal) {
      this(turnId, countryId, proposal, null);
    }

    public SubmitDecision {
      NodeAddress.requireSegment(turnId);
      if ((countryId == null) == (decisionMakerId == null)) throw new IllegalArgumentException("收件需要唯一的提交身份");
      NodeAddress.requireSegment(decisionMakerId == null ? countryId : decisionMakerId);
      requireValue(proposal, "proposal");
    }

    public String participantId() { return decisionMakerId == null ? countryId : decisionMakerId; }
  }

  record ResolveTurn(String turnId, Map<String, TurnState.Proposal> proposals, String resolution,
                     List<TurnState.Ruling> rulings) implements WorldChange {
    public ResolveTurn {
      NodeAddress.requireSegment(turnId);
      proposals = Map.copyOf(proposals);
      rulings = rulings == null ? null : List.copyOf(rulings);
    }
  }

  private static void requireId(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不得为空白");
  }

  private static void requireValue(Object value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " 不得为空");
  }
}
