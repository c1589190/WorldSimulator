package io.mosire.worldsim.orbat;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.Evidence;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Formation documents and the effective runtime units at a timeline node. */
public record OrbatState(Map<String, OrbatDocument> documents, Map<String, OrbatDocument> priorIdeals,
                         Map<String, Unit> units, @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, March> marches,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Battle> battles) {
  public static final OrbatState EMPTY = new OrbatState(Map.of(), Map.of(), Map.of());

  public OrbatState(Map<String, OrbatDocument> documents, Map<String, OrbatDocument> priorIdeals, Map<String, Unit> units) {
    this(documents, priorIdeals, units, Map.of(), Map.of());
  }

  public record Ref(String documentId, String nodeId, String contentHash) {
    public Ref {
      NodeAddress.requireSegment(documentId);
      NodeAddress.requireSegment(nodeId);
      if (contentHash != null && !contentHash.matches("sha256:[a-f0-9]{64}")) {
        throw new IllegalArgumentException("无效编制内容 hash");
      }
    }
  }

  public record Unit(String id, String name, String type, String countryId, String provinceId,
                     Ref idealRef, Ref actualRef, Evidence evidence, Evidence deploymentEvidence) {
    public Unit {
      NodeAddress.requireSegment(id);
      if (name == null || name.isBlank()) throw new IllegalArgumentException("单位名称不得为空");
      if (countryId != null) NodeAddress.requireSegment(countryId);
      if (provinceId != null) NodeAddress.requireSegment(provinceId);
      Objects.requireNonNull(evidence, "单位来源");
      if (provinceId != null && deploymentEvidence == null) deploymentEvidence = evidence;
    }

    public Unit withDeployment(String province, Evidence source) {
      return new Unit(id, name, type, countryId, province, idealRef, actualRef, evidence, source);
    }
  }

  public OrbatState {
    documents = Map.copyOf(documents);
    priorIdeals = Map.copyOf(priorIdeals);
    units = Map.copyOf(units);
    marches = marches == null ? Map.of() : Map.copyOf(marches);
    battles = battles == null ? Map.of() : Map.copyOf(battles);
    for (var entry : documents.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().documentId())) throw new IllegalArgumentException("编制文档键与 ID 不一致");
    }
    for (var entry : units.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("单位键与 ID 不一致");
    }
    for (var entry : battles.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("战斗键与 ID 不一致");
    }
  }

  public OrbatState withDocument(OrbatDocument document) {
    var next = new TreeMap<>(documents);
    var previous = next.put(document.documentId(), document);
    if (previous != null && !previous.role().equals(document.role())) {
      throw new IllegalArgumentException("同一编制文档的口径须一致: " + document.documentId());
    }
    if (previous == null || !document.role().equals("ideal") || previous.equals(document)) {
      return new OrbatState(next, priorIdeals, units, marches, battles);
    }
    var versions = new TreeMap<>(priorIdeals);
    versions.put(previous.contentHash(), previous);
    versions.remove(document.contentHash());
    return new OrbatState(next, versions, units, marches, battles);
  }

  public OrbatState withUnit(Unit unit, String time) {
    Unit old = units.get(unit.id());
    var march = marches.get(unit.id());
    Map<String, March> orders = marches;
    if (march != null && old != null && !Objects.equals(old.provinceId(), unit.provinceId())) {
      if (march.progress(time).moving()) throw new IllegalArgumentException("调整部署前须取消行军: " + unit.id());
      orders = new TreeMap<>(marches);
      orders.remove(unit.id());
    }
    var next = new TreeMap<>(units);
    next.put(unit.id(), unit);
    return new OrbatState(documents, priorIdeals, next, orders, battles);
  }

  public Unit bindIdeal(Unit unit) {
    Ref ideal = unit.idealRef();
    if (ideal == null || ideal.contentHash() != null) return unit;
    var document = documents.get(ideal.documentId());
    if (document == null || !document.role().equals("ideal")) {
      throw new IllegalArgumentException("理想编制不存在: " + ideal.documentId());
    }
    return new Unit(unit.id(), unit.name(), unit.type(), unit.countryId(), unit.provinceId(),
        new Ref(ideal.documentId(), ideal.nodeId(), document.contentHash()), unit.actualRef(),
        unit.evidence(), unit.deploymentEvidence());
  }

  public OrbatDocument findDocument(String documentId, String hash) {
    var current = documents.get(documentId);
    if (hash == null) return current;
    if (current != null && current.role().equals("ideal") && hash.equals(current.contentHash())) return current;
    var earlier = priorIdeals.get(hash);
    return earlier != null && earlier.documentId().equals(documentId) ? earlier : null;
  }

  public OrbatState deploy(String unitId, String provinceId, Evidence evidence, String time) {
    Unit unit = units.get(unitId);
    if (unit == null) throw new IllegalArgumentException("单位不存在: " + unitId);
    return withUnit(unit.withDeployment(provinceId, evidence), time);
  }

  public OrbatState startMarch(String unitId, March march, String time) {
    Unit unit = units.get(unitId);
    if (unit == null || !march.legs().getFirst().from().equals(unit.provinceId())) {
      throw new IllegalArgumentException("行军起点须为单位当前部署省: " + unitId);
    }
    if (!march.startedAt().equals(time) || march.cancelledAt() != null || march.blockage() != null) {
      throw new IllegalArgumentException("行军须从当前剧本时间出发");
    }
    March existing = marches.get(unitId);
    if (existing != null && existing.progress(time).moving()) {
      throw new IllegalArgumentException("该单位已有进行中的行军: " + unitId);
    }
    return withMarch(unitId, march);
  }

  public OrbatState cancelMarch(String unitId, String time) {
    March existing = marches.get(unitId);
    if (existing == null) throw new IllegalArgumentException("该单位没有行军记录: " + unitId);
    return withMarch(unitId, existing.cancel(time));
  }

  private OrbatState withMarch(String unitId, March march) {
    var orders = new TreeMap<>(marches);
    orders.put(unitId, march);
    return new OrbatState(documents, priorIdeals, units, orders, battles);
  }

  public OrbatState advanceMarches(String previousTime, String time, CountryState countries) {
    if (marches.isEmpty()) return this;
    var next = new TreeMap<>(units);
    var orders = new TreeMap<>(marches);
    for (var entry : marches.entrySet()) {
      March march = entry.getValue();
      Unit unit = units.get(entry.getKey());
      var previous = march.progress(previousTime);
      var progress = march.progress(time);
      if (previous.moving()) {
        for (int i = previous.reachedLegs(); i < progress.reachedLegs(); i++) {
          var leg = march.legs().get(i);
          if (leg.conditions() == null) continue;
          var passage = countries.entry(unit.countryId(), leg.to());
          if (passage.blocked()) {
            march = march.block(i, passage);
            orders.put(unit.id(), march);
            progress = march.progress(time);
            break;
          }
        }
      }
      String province = progress.provinceId();
      if (!province.equals(unit.provinceId())) next.put(unit.id(), unit.withDeployment(province, march.evidence()));
    }
    return new OrbatState(documents, priorIdeals, next, orders, battles);
  }

  public OrbatState resolveBattle(Battle battle, String time) {
    if (battles.containsKey(battle.id())) throw new IllegalArgumentException("战斗已经结算: " + battle.id());
    if (!battle.endedAt().equals(time)) throw new IllegalArgumentException("战斗结束时间须与当前剧本时钟一致");
    var orders = new TreeMap<>(marches);
    for (String id : battle.participants()) {
      if (!units.containsKey(id)) throw new IllegalArgumentException("参战单位不存在: " + id);
      March march = orders.get(id);
      if (march != null && march.progress(time).moving()) orders.put(id, march.cancel(time));
    }
    var records = new TreeMap<>(battles);
    records.put(battle.id(), battle);
    var updated = new TreeMap<>(documents);
    for (var loss : battle.losses()) {
      var unit = units.get(loss.unitId());
      var ref = unit.actualRef();
      var doc = updated.get(loss.documentId());
      if (ref == null || doc == null || !ref.documentId().equals(loss.documentId())) {
        throw new IllegalArgumentException("无法解析战损的实编基数: " + loss.unitId());
      }
      var root = doc.findNode(ref.nodeId());
      if (root == null || OrbatDocument.findNode(root, loss.nodeId()) == null) {
        throw new IllegalArgumentException("无法解析战损的实编基数: " + loss.unitId());
      }
      updated.put(loss.documentId(), battle.applyLoss(doc, loss));
    }
    return new OrbatState(updated, priorIdeals, units, orders, records);
  }

  public void validate(WorldMap map, CountryState countries, String time) {
    for (var version : priorIdeals.entrySet()) {
      if (!version.getValue().role().equals("ideal") || !version.getKey().equals(version.getValue().contentHash())) {
        throw new IllegalArgumentException("理想编制版本不匹配: " + version.getKey());
      }
    }
    var actualRoots = new HashSet<Ref>();
    for (Unit unit : units.values()) {
      if (unit.provinceId() != null && !map.provinces().containsKey(unit.provinceId())) {
        throw new IllegalArgumentException("单位部署省不存在: " + unit.id() + " → " + unit.provinceId());
      }
      if (unit.countryId() != null && !countries.countries().containsKey(unit.countryId())) {
        throw new IllegalArgumentException("单位国家不存在: " + unit.id() + " → " + unit.countryId());
      }
      requireReference(unit.idealRef(), "ideal");
      requireReference(unit.actualRef(), "actual");
      if (unit.actualRef() != null && !actualRoots.add(unit.actualRef())) {
        throw new IllegalArgumentException("多个单位使用同一实际编制节点: " + unit.actualRef());
      }
    }
    validateMarches(map, time);
  }

  private void validateMarches(WorldMap map, String time) {
    var required = new HashSet<List<String>>();
    for (var entry : marches.entrySet()) {
      Unit unit = units.get(entry.getKey());
      March march = entry.getValue();
      var progress = march.progress(time);
      if (unit == null || !progress.provinceId().equals(unit.provinceId())) {
        throw new IllegalArgumentException("行军进度与部署省不一致: " + entry.getKey());
      }
      if (!progress.moving()) continue;
      for (var leg : march.legs()) {
        var from = map.provinces().get(leg.from());
        var to = map.provinces().get(leg.to());
        if (from == null || to == null || !from.kind().equals("land") || !to.kind().equals("land")) {
          throw new IllegalArgumentException("进行中的行军须沿陆地省份: " + entry.getKey());
        }
        required.add(leg.from().compareTo(leg.to()) < 0 ? List.of(leg.from(), leg.to()) : List.of(leg.to(), leg.from()));
      }
    }
    if (!required.isEmpty()) {
      for (var edge : map.adjacencies()) {
        if (edge.permitsLandMarch()) required.remove(List.of(edge.a(), edge.b()));
        if (required.isEmpty()) return;
      }
      throw new IllegalArgumentException("进行中的行军路线缺少普通陆地邻接");
    }
  }

  private void requireReference(Ref reference, String role) {
    if (reference == null) return;
    if (role.equals("ideal") != (reference.contentHash() != null)) {
      throw new IllegalArgumentException("理想引用需绑定版本，实际引用依照节点读取");
    }
    var document = findDocument(reference.documentId(), reference.contentHash());
    if (document == null || !document.role().equals(role) || document.findNode(reference.nodeId()) == null) {
      throw new IllegalArgumentException("编制引用无法解析: " + reference);
    }
  }
}
