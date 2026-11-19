package io.mosire.worldsim.orbat;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.Evidence;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Formation documents and the effective runtime units at a timeline node. */
public record OrbatState(Map<String, OrbatDocument> documents, Map<String, OrbatDocument> priorIdeals,
                         Map<String, Unit> units) {
  public static final OrbatState EMPTY = new OrbatState(Map.of(), Map.of(), Map.of());

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
    for (var entry : documents.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().documentId())) throw new IllegalArgumentException("编制文档键与 ID 不一致");
    }
    for (var entry : units.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("单位键与 ID 不一致");
    }
  }

  public OrbatState withDocument(OrbatDocument document) {
    var next = new TreeMap<>(documents);
    var previous = next.put(document.documentId(), document);
    if (previous != null && !previous.role().equals(document.role())) {
      throw new IllegalArgumentException("同一编制文档的口径须一致: " + document.documentId());
    }
    if (previous == null || !document.role().equals("ideal") || previous.equals(document)) {
      return new OrbatState(next, priorIdeals, units);
    }
    var versions = new TreeMap<>(priorIdeals);
    versions.put(previous.contentHash(), previous);
    versions.remove(document.contentHash());
    return new OrbatState(next, versions, units);
  }

  public OrbatState withUnit(Unit unit) {
    var next = new TreeMap<>(units);
    next.put(unit.id(), unit);
    return new OrbatState(documents, priorIdeals, next);
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

  public OrbatState deploy(String unitId, String provinceId, Evidence evidence) {
    Unit unit = units.get(unitId);
    if (unit == null) throw new IllegalArgumentException("单位不存在: " + unitId);
    return withUnit(unit.withDeployment(provinceId, evidence));
  }

  public void validate(WorldMap map, CountryState countries) {
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
