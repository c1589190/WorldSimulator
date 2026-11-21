package io.mosire.worldsim.orbat;

import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.state.Evidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Operations on directly assigned quantities in actual formations. */
public final class FormationStock {
  private FormationStock() {}

  public record Scope(OrbatDocument document, OrbatDocument.Node root) {
    public OrbatDocument.Node find(String id) { return OrbatDocument.findNode(root, id); }
  }

  public record Change(String nodeId, String itemId, String name, int before, int after) {
    public Change {
      NodeAddress.requireSegment(nodeId);
      if (itemId != null) NodeAddress.requireSegment(itemId);
      if (before < 0 || after < 0 || before == after) throw new IllegalArgumentException("无效的实编数量变更");
    }

    public int delta() { return after - before; }
  }

  public static Scope actual(OrbatState state, OrbatState.Unit unit) {
    var ref = unit.actualRef();
    if (ref == null) return null;
    var document = state.documents().get(ref.documentId());
    var root = document == null ? null : document.findNode(ref.nodeId());
    if (root == null || !document.role().equals("actual")) throw new IllegalArgumentException("无法解析实际编制: " + unit.id());
    return new Scope(document, root);
  }

  public static List<OrbatDocument.Node> nodes(OrbatDocument.Node root) {
    var pending = new ArrayDeque<OrbatDocument.Node>();
    var result = new ArrayList<OrbatDocument.Node>();
    pending.add(root);
    while (!pending.isEmpty()) {
      var node = pending.removeFirst();
      result.add(node);
      pending.addAll(node.children());
    }
    return List.copyOf(result);
  }

  public static OrbatDocument.Equipment equipment(OrbatDocument.Inventory inventory, String itemId) {
    OrbatDocument.Equipment selected = null;
    for (var entry : inventory.items()) {
      if (!itemId.equals(entry.itemId())) continue;
      if (selected != null) throw new IllegalArgumentException("装备 ID 在同一库存内有多条记录: " + itemId);
      selected = entry;
    }
    return selected;
  }

  public static Integer equipmentCount(OrbatDocument.Inventory inventory, String itemId) {
    return equipmentCount(inventory, equipment(inventory, itemId));
  }

  private static Integer equipmentCount(OrbatDocument.Inventory inventory, OrbatDocument.Equipment item) {
    if (item != null) return item.quantity().value();
    if (inventory.coverage().equals("complete") && inventory.items().stream().allMatch(e -> e.itemId() != null)) return 0;
    return null;
  }

  private record Holder(String nodeId, String name, int available) {}

  public static List<Change> spread(OrbatDocument.Node root, String itemId, int count) {
    if (count <= 0) throw new IllegalArgumentException("均摊扣减数量须为正整数");
    var holders = new ArrayList<Holder>();
    long available = 0;
    for (var node : nodes(root)) {
      var entry = equipment(node.equipment(), itemId);
      if (entry != null && entry.quantity().value() != null && entry.quantity().value() > 0) {
        int quantity = entry.quantity().value();
        holders.add(new Holder(node.nodeId(), entry.name(), quantity));
        available += quantity;
      }
    }
    if (available < count) return List.of();
    holders.sort(Comparator.comparingInt(Holder::available).thenComparing(Holder::nodeId));
    var result = new TreeMap<String, Change>();
    int remaining = count;
    int first = 0;
    while (first < holders.size() && holders.get(first).available() <= remaining / (holders.size() - first)) {
      var holder = holders.get(first++);
      result.put(holder.nodeId(), new Change(holder.nodeId(), itemId, holder.name(), holder.available(), 0));
      remaining -= holder.available();
    }
    if (remaining > 0) {
      var active = new ArrayList<>(holders.subList(first, holders.size()));
      active.sort(Comparator.comparing(Holder::nodeId));
      int share = remaining / active.size();
      int remainder = remaining % active.size();
      for (int index = 0; index < active.size(); index++) {
        int deduction = share + (index < remainder ? 1 : 0);
        if (deduction == 0) break;
        var holder = active.get(index);
        result.put(holder.nodeId(), new Change(holder.nodeId(), itemId, holder.name(), holder.available(), holder.available() - deduction));
      }
    }
    return List.copyOf(result.values());
  }

  public static OrbatDocument.Inventory updateEquipment(OrbatDocument.Inventory inventory, String itemId,
      String name, int before, int after, Evidence evidence) {
    var existing = equipment(inventory, itemId);
    Integer current = equipmentCount(inventory, existing);
    if (current == null || current != before) throw new IllegalArgumentException("装备变更与实有数量不一致: " + itemId);
    var original = existing == null ? inventory.evidence() : evidence(existing.quantity());
    var replacement = new OrbatDocument.Equipment(itemId, existing == null ? name : existing.name(), quantity(after, original, evidence));
    var items = new ArrayList<>(inventory.items());
    if (existing == null) items.add(replacement);
    else items.set(items.indexOf(existing), replacement);
    return new OrbatDocument.Inventory(inventory.coverage(), items, inventory.evidence());
  }

  public static OrbatState apply(OrbatState state, String unitId, String documentId, List<Change> changes, Evidence evidence) {
    var unit = state.units().get(unitId);
    var scope = unit == null ? null : actual(state, unit);
    if (scope == null || !scope.document().documentId().equals(documentId)) {
      throw new IllegalArgumentException("数量变更需要目标单位的实际编制: " + unitId);
    }
    for (var other : state.units().values()) {
      var ref = other.actualRef();
      if (other.id().equals(unitId) || ref == null || !ref.documentId().equals(documentId)) continue;
      var otherRoot = scope.document().findNode(ref.nodeId());
      if (scope.find(ref.nodeId()) != null || otherRoot != null && OrbatDocument.findNode(otherRoot, scope.root().nodeId()) != null) {
        throw new IllegalArgumentException("部署单位的实际编制范围重叠: " + unitId + " / " + other.id());
      }
    }
    var pending = new HashMap<String, Change>();
    for (var change : changes) {
      if (pending.putIfAbsent(change.nodeId(), change) != null) throw new IllegalArgumentException("同一批次不能重复修改实编节点");
    }
    var root = apply(scope.root(), pending, evidence);
    if (!pending.isEmpty()) throw new IllegalArgumentException("数量变更节点不属于目标单位的实际编制");
    return state.withDocument(scope.document().replaceNode(root));
  }

  private static OrbatDocument.Node apply(OrbatDocument.Node node, Map<String, Change> pending, Evidence cause) {
    if (pending.isEmpty()) return node;
    var change = pending.remove(node.nodeId());
    var children = node.children().stream().map(child -> apply(child, pending, cause)).toList();
    if (change == null && children.equals(node.children())) return node;
    var personnel = node.personnel();
    var inventory = node.equipment();
    if (change != null) {
      if (change.itemId() == null) {
        if (personnel.value() == null || personnel.value() != change.before()) {
          throw new IllegalArgumentException("人员变更与实有数量不一致: " + node.nodeId());
        }
        personnel = quantity(change.after(), evidence(personnel), cause);
      } else {
        inventory = updateEquipment(inventory, change.itemId(), change.name(), change.before(), change.after(), cause);
      }
    }
    return new OrbatDocument.Node(node.nodeId(), node.name(), node.type(), node.echelon(),
        personnel, inventory, children, node.extensions(), node.marchSpeed());
  }

  private static Evidence evidence(OrbatDocument.Quantity quantity) {
    return new Evidence(quantity.source(), quantity.status(), quantity.confidence(), null);
  }

  private static OrbatDocument.Quantity quantity(int count, Evidence previous, Evidence cause) {
    var next = Evidence.afterAdjustment(previous, cause);
    return new OrbatDocument.Quantity(count, next.status(), next.source(), next.confidence());
  }
}
