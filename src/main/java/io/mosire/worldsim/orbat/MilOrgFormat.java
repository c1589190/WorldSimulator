package io.mosire.worldsim.orbat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.worldsim.json.JsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** DSH mil-org v1 exchange. Native document identity belongs to WorldSimulator. */
public final class MilOrgFormat {
  private static final String EXTENSION = "dsh.mil-org";

  private MilOrgFormat() {}

  public static OrbatDocument read(String documentId, String role, String source, JsonNode data) {
    if (source == null || source.isBlank()) throw new IllegalArgumentException("mil-org 导入需要来源");
    if (data == null || !"mil-org".equals(text(data, "kind")) || !data.path("version").isIntegralNumber()
        || !data.path("version").canConvertToInt() || data.path("version").intValue() != 1) {
      throw new IllegalArgumentException("需要 DSH mil-org v1 文档");
    }
    return new OrbatDocument(OrbatDocument.FORMAT, documentId, role, null, readNode(data.get("data"), "root", source));
  }

  private static OrbatDocument.Node readNode(JsonNode input, String path, String source) {
    if (!(input instanceof ObjectNode object)) throw new IllegalArgumentException("mil-org 编制节点须为对象");
    String id = text(input, "id");
    if (id == null) id = path;
    List<OrbatDocument.Node> children = new ArrayList<>();
    JsonNode tree = input.get("children");
    if (tree != null) {
      if (!tree.isArray()) throw new IllegalArgumentException("mil-org children 须为数组");
      for (int i = 0; i < tree.size(); i++) {
        children.add(readNode(tree.get(i), path + "." + (i + 1), source));
      }
    }
    List<OrbatDocument.Equipment> equipment = new ArrayList<>();
    JsonNode listed = input.get("equipment");
    if (listed != null) {
      if (!listed.isArray()) throw new IllegalArgumentException("mil-org equipment 须为数组");
      for (JsonNode item : listed) {
        equipment.add(new OrbatDocument.Equipment(null, text(item, "name"), quantity(item.get("count"), source)));
      }
    }
    var inventory = new OrbatDocument.Inventory(equipment.isEmpty() ? "unknown" : "partial", equipment, null);
    ObjectNode original = object.deepCopy();
    original.remove(List.of("id", "name", "type", "echelon", "children"));
    return new OrbatDocument.Node(id, text(input, "name"), text(input, "type"), text(input, "echelon"),
        quantity(input.get("personnel"), source), inventory, children,
        original.isEmpty() ? Map.of() : Map.of(EXTENSION, new OrbatDocument.Extension(1, original)));
  }

  private static OrbatDocument.Quantity quantity(JsonNode raw, String source) {
    if (raw == null || raw.isNull()) return new OrbatDocument.Quantity(null, "unknown", source, null);
    if (!raw.isIntegralNumber() || !raw.canConvertToInt() || raw.intValue() < 0) {
      throw new IllegalArgumentException("mil-org 数量须为非负整数");
    }
    if (raw.intValue() == 0) return new OrbatDocument.Quantity(null, "legacy-ambiguous", source, null);
    return new OrbatDocument.Quantity(raw.intValue(), "known", source, null);
  }

  private static String text(JsonNode data, String key) {
    JsonNode value = data.get(key);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  public static Map<String, Object> write(OrbatDocument document, OrbatDocument.Node subtree) {
    Tree tree = writeNode(subtree);
    Map<String, Object> data = Map.of("kind", "mil-org", "version", 1, "app", "WorldSimulator",
        "name", subtree.name(), "personnel", tree.personnel, "equipTotal", tree.equipment, "data", tree.data);
    List<Map<String, String>> losses = new ArrayList<>();
    losses.add(Map.of("code", "native-ids", "message", "mil-org 不携带原生文档及编制节点 ID"));
    losses.add(Map.of("code", "native-evidence", "message", "编制口径、来源、置信度和原生扩展需随原生文档保存"));
    if (tree.incomplete) losses.add(Map.of("code", "incomplete-strength",
        "message", "未知数量以兼容格式的零占位，未覆盖的装备不计入兼容合计"));
    return Map.of("format", "mil-org", "role", document.role(), "document", data, "lossReport", losses);
  }

  private record Tree(ObjectNode data, long personnel, long equipment, boolean incomplete) {}

  private static Tree writeNode(OrbatDocument.Node node) {
    var source = node.extensions().get(EXTENSION);
    JsonNode legacy = source == null ? null : source.data();
    ObjectNode out = legacy instanceof ObjectNode object ? object : JsonCodec.mapper().createObjectNode();
    out.remove("id");
    JsonNode originalEquipment = out.remove("equipment");
    out.put("name", node.name());
    if (node.type() != null) out.put("type", node.type());
    if (node.echelon() != null) out.put("echelon", node.echelon());
    long personnel = amount(node.personnel());
    long equipment = 0;
    boolean incomplete = node.personnel().value() == null || !node.equipment().coverage().equals("complete");
    out.put("personnel", personnel);
    var items = out.putArray("equipment");
    for (OrbatDocument.Equipment item : node.equipment().items()) {
      String name = item.name() == null ? item.itemId() : item.name();
      ObjectNode legacyItem = JsonCodec.mapper().createObjectNode();
      if (originalEquipment != null && originalEquipment.isArray()) {
        for (JsonNode entry : originalEquipment) {
          if (entry instanceof ObjectNode original && name.equals(text(entry, "name"))) {
            legacyItem = original.deepCopy();
            break;
          }
        }
      }
      legacyItem.put("name", name);
      legacyItem.put("count", amount(item.quantity()));
      items.add(legacyItem);
      equipment += amount(item.quantity());
      incomplete |= item.quantity().value() == null;
    }
    var children = out.putArray("children");
    for (OrbatDocument.Node child : node.children()) {
      Tree nested = writeNode(child);
      children.add(nested.data);
      personnel += nested.personnel;
      equipment += nested.equipment;
      incomplete |= nested.incomplete;
    }
    return new Tree(out, personnel, equipment, incomplete);
  }

  private static int amount(OrbatDocument.Quantity quantity) {
    return quantity.value() == null ? 0 : quantity.value();
  }
}
