package io.mosire.worldsim.orbat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.state.Evidence;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The shared tree format for ideal and actual formations. */
public record OrbatDocument(String format, String documentId, String role, String date, Node root) {
  public static final String FORMAT = "orbat-native-v1";

  public record Quantity(@JsonInclude(JsonInclude.Include.ALWAYS) Integer value, String status,
                         String source, @JsonInclude(JsonInclude.Include.ALWAYS) Double confidence) {
    public static final Quantity UNKNOWN = new Quantity(null, "unknown", null, null);

    public Quantity {
      new Evidence(source, status, confidence, null);
      switch (status) {
        case "known", "estimated" -> {
          if (value == null || value < 0) throw new IllegalArgumentException("已知或估计数量须为非负整数");
        }
        case "unknown", "legacy-ambiguous" -> {
          if (value != null) throw new IllegalArgumentException("未知或语义有歧义的数量须为 null");
        }
        default -> throw new IllegalArgumentException("无效编制数量状态: " + status);
      }
    }

    @JsonCreator
    public static Quantity fromJson(@JsonProperty("value") JsonNode count, @JsonProperty("status") String status,
                                    @JsonProperty("source") String source,
                                    @JsonProperty("confidence") Double confidence) {
      if (count == null || count.isNull()) return new Quantity(null, status, source, confidence);
      if (!count.isIntegralNumber() || !count.canConvertToInt()) {
        throw new IllegalArgumentException("编制数量须为整数");
      }
      return new Quantity(count.intValue(), status, source, confidence);
    }
  }

  public record Equipment(String itemId, String name, Quantity quantity) {
    public Equipment {
      if (itemId != null) NodeAddress.requireSegment(itemId);
      if (itemId == null && (name == null || name.isBlank())) {
        throw new IllegalArgumentException("装备需要目录 ID 或来源名称");
      }
      Objects.requireNonNull(quantity, "装备数量");
    }
  }

  public record Inventory(String coverage, List<Equipment> items, Evidence evidence) {
    public static final Inventory UNKNOWN = new Inventory("unknown", List.of(), null);

    public Inventory {
      items = items == null ? List.of() : List.copyOf(items);
      if (coverage == null || !Set.of("complete", "partial", "unknown").contains(coverage)) {
        throw new IllegalArgumentException("无效装备清单覆盖: " + coverage);
      }
      if (coverage.equals("unknown") && !items.isEmpty()) {
        throw new IllegalArgumentException("存在装备记录时应标明清单为部分或完整");
      }
      if (coverage.equals("complete") && (evidence == null || !evidence.status().equals("known"))) {
        throw new IllegalArgumentException("完整清单需要来源确认");
      }
    }
  }

  public record Extension(int version, JsonNode data) {
    public Extension {
      if (version < 1 || data == null) throw new IllegalArgumentException("编制扩展需要版本及数据");
      data = data.deepCopy();
    }

    @Override public JsonNode data() {
      return data.deepCopy();
    }
  }

  public record MarchSpeed(double kmPerHour, Evidence evidence) {
    public MarchSpeed {
      if (!Double.isFinite(kmPerHour) || kmPerHour < 0) {
        throw new IllegalArgumentException("行军速度须为非负有限数");
      }
      if (evidence == null || !Set.of("known", "estimated", "inferred").contains(evidence.status())) {
        throw new IllegalArgumentException("行军速度须有来源，未知速度可省略");
      }
    }

    @JsonCreator
    public static MarchSpeed fromJson(@JsonProperty("kmPerHour") JsonNode speed,
                                      @JsonProperty("evidence") Evidence evidence) {
      if (speed == null || !speed.isNumber()) throw new IllegalArgumentException("行军速度须为数值");
      return new MarchSpeed(speed.doubleValue(), evidence);
    }
  }

  public record Node(String nodeId, String name, String type, String echelon, Quantity personnel,
                     Inventory equipment, List<Node> children, Map<String, Extension> extensions,
                     MarchSpeed marchSpeed) {
    public Node {
      NodeAddress.requireSegment(nodeId);
      if (name == null || name.isBlank()) throw new IllegalArgumentException("编制节点名称不得为空");
      personnel = personnel == null ? Quantity.UNKNOWN : personnel;
      equipment = equipment == null ? Inventory.UNKNOWN : equipment;
      children = children == null ? List.of() : List.copyOf(children);
      extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
      if (extensions.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("编制扩展需要命名空间");
    }

    public Node(String nodeId, String name, String type, String echelon, Quantity personnel,
                Inventory equipment, List<Node> children, Map<String, Extension> extensions) {
      this(nodeId, name, type, echelon, personnel, equipment, children, extensions, null);
    }
  }

  public OrbatDocument {
    if (!FORMAT.equals(format)) throw new IllegalArgumentException("不支持的原生编制格式: " + format);
    NodeAddress.requireSegment(documentId);
    if (!"ideal".equals(role) && !"actual".equals(role)) throw new IllegalArgumentException("编制口径须为 ideal 或 actual");
    if (date != null) LocalDate.parse(date);
    Objects.requireNonNull(root, "编制根");
    var queue = new ArrayDeque<Node>();
    var seen = new HashSet<String>();
    queue.add(root);
    while (!queue.isEmpty()) {
      Node node = queue.removeFirst();
      if (!seen.add(node.nodeId())) throw new IllegalArgumentException("编制节点 ID 重复: " + node.nodeId());
      queue.addAll(node.children());
    }
  }

  public Node findNode(String id) {
    return findNode(root, id);
  }

  static Node findNode(Node root, String id) {
    var queue = new ArrayDeque<Node>();
    queue.add(root);
    while (!queue.isEmpty()) {
      Node next = queue.removeFirst();
      if (next.nodeId().equals(id)) return next;
      queue.addAll(next.children());
    }
    return null;
  }

  OrbatDocument replaceNode(Node replacement) {
    return new OrbatDocument(format, documentId, role, date, replace(root, replacement));
  }

  private static Node replace(Node node, Node replacement) {
    if (node.nodeId().equals(replacement.nodeId())) return replacement;
    if (node.children().isEmpty()) return node;
    var children = node.children().stream().map(child -> replace(child, replacement)).toList();
    return new Node(node.nodeId(), node.name(), node.type(), node.echelon(), node.personnel(),
        node.equipment(), children, node.extensions(), node.marchSpeed());
  }
  public String contentHash() {
    return JsonCodec.sha256(JsonCodec.canonicalJson(this));
  }
}
