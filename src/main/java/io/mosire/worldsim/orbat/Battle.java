package io.mosire.worldsim.orbat;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.timeline.SimulationTime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One resolved engagement. Quantities refer to direct strength at the time of resolution. */
public record Battle(String id, String provinceId, String base, String baseHash, String startedAt, String endedAt,
                     List<String> attackers, List<String> defenders, String outcome, String rationale, Evidence evidence,
                     List<Loss> losses, Control control) {
  public record Count(int lost, Integer before, Integer after) {
    public Count {
      if (lost <= 0 || (before == null) != (after == null)
          || before != null && (before < lost || after != before - lost)) {
        throw new IllegalArgumentException("损耗数量与扣减前后数量不一致");
      }
    }

    public static Count of(Integer before, int lost) {
      if (lost <= 0) throw new IllegalArgumentException("损耗须为正整数");
      if (before != null && lost > before) throw new IllegalArgumentException("损耗超过已记录的实有数量");
      return new Count(lost, before, before == null ? null : before - lost);
    }
  }

  public record EquipmentLoss(int index, String itemId, String name, Count quantity) {
    public EquipmentLoss {
      if (index < 0 || itemId == null && (name == null || name.isBlank())) {
        throw new IllegalArgumentException("装备损耗缺少库存条目");
      }
      Objects.requireNonNull(quantity, "装备损耗数量");
    }
  }

  public record Loss(String unitId, String documentId, String nodeId, Count personnel, List<EquipmentLoss> equipment) {
    public Loss {
      NodeAddress.requireSegment(unitId);
      NodeAddress.requireSegment(documentId);
      NodeAddress.requireSegment(nodeId);
      equipment = equipment == null ? List.of() : List.copyOf(equipment);
      var slots = new HashSet<Integer>();
      if (equipment.stream().anyMatch(item -> !slots.add(item.index())) || personnel == null && equipment.isEmpty()) {
        throw new IllegalArgumentException("直属损耗需要至少一个字段，每个装备条目只能扣减一次");
      }
    }
  }

  public record Control(String previousCountryId, String countryId) {
    public Control {
      if (previousCountryId != null) NodeAddress.requireSegment(previousCountryId);
      NodeAddress.requireSegment(countryId);
    }
  }

  public Battle {
    NodeAddress.requireSegment(id);
    NodeAddress.requireSegment(provinceId);
    if (NodeAddress.parse(base).path().size() != 0 || baseHash == null || !baseHash.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("战斗须绑定基准节点与 hash");
    }
    startedAt = SimulationTime.normalize(startedAt);
    endedAt = SimulationTime.normalize(endedAt);
    if (startedAt.compareTo(endedAt) > 0) throw new IllegalArgumentException("战斗开始时间晚于结束时间");
    attackers = List.copyOf(attackers);
    defenders = List.copyOf(defenders);
    var participants = new HashSet<String>();
    if (attackers.isEmpty() || defenders.isEmpty() || !addSide(participants, attackers) || !addSide(participants, defenders)) {
      throw new IllegalArgumentException("战斗双方均需有参与单位，单位不得重复");
    }
    if (outcome == null || !Set.of("attacker-victory", "defender-victory", "draw", "disengaged").contains(outcome)
        || rationale == null || rationale.isBlank() || evidence == null
        || !Set.of("known", "inferred", "estimated").contains(evidence.status())) {
      throw new IllegalArgumentException("战斗裁决需要有效结果、依据和证据");
    }
    losses = losses == null ? List.of() : List.copyOf(losses);
    var affected = new HashSet<List<String>>();
    for (Loss loss : losses) {
      if (!participants.contains(loss.unitId()) || !affected.add(List.of(loss.documentId(), loss.nodeId()))) {
        throw new IllegalArgumentException("直属损耗须属于参战单位且只记录一次");
      }
    }
  }

  private static boolean addSide(Set<String> participants, List<String> side) {
    for (String unit : side) {
      NodeAddress.requireSegment(unit);
      if (!participants.add(unit)) return false;
    }
    return true;
  }

  List<String> participants() {
    var result = new ArrayList<>(attackers);
    result.addAll(defenders);
    return result;
  }

  OrbatDocument applyLoss(OrbatDocument document, Loss loss) {
    if (document == null || !document.role().equals("actual")) throw new IllegalArgumentException("损耗须写入实际编制");
    var node = document.findNode(loss.nodeId());
    if (node == null) throw new IllegalArgumentException("损耗节点不存在: " + loss.nodeId());
    var personnel = loss.personnel() == null ? node.personnel() : after(node.personnel(), loss.personnel());
    var items = new ArrayList<>(node.equipment().items());
    for (EquipmentLoss entry : loss.equipment()) {
      if (entry.index() >= items.size()) throw new IllegalArgumentException("损耗装备不存在");
      var item = items.get(entry.index());
      if (!Objects.equals(item.itemId(), entry.itemId()) || !Objects.equals(item.name(), entry.name())) {
        throw new IllegalArgumentException("损耗装备与实编条目不一致");
      }
      items.set(entry.index(), new OrbatDocument.Equipment(item.itemId(), item.name(), after(item.quantity(), entry.quantity())));
    }
    var inventory = new OrbatDocument.Inventory(node.equipment().coverage(), items, node.equipment().evidence());
    return document.replaceNode(new OrbatDocument.Node(node.nodeId(), node.name(), node.type(), node.echelon(),
        personnel, inventory, node.children(), node.extensions(), node.marchSpeed()));
  }

  private OrbatDocument.Quantity after(OrbatDocument.Quantity original, Count loss) {
    if (!Objects.equals(original.value(), loss.before())) throw new IllegalArgumentException("损耗基数与实有编制不一致");
    if (loss.after() == null) return original;
    String status = original.status().equals("known") && evidence.status().equals("known") ? "known" : "estimated";
    Double confidence = original.confidence() != null && evidence.confidence() != null
        ? Math.min(original.confidence(), evidence.confidence()) : null;
    return new OrbatDocument.Quantity(loss.after(), status, "battle:" + id + " · " + evidence.source(), confidence);
  }
}
