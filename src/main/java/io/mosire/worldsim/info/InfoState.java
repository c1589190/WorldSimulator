package io.mosire.worldsim.info;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.state.Evidence;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record InfoState(Map<String, Entry> entries, @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Document> documents,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Contact> contacts) {
  public InfoState(Map<String, Entry> entries) { this(entries, null, null); }
  public static final InfoState EMPTY = new InfoState(Map.of());

  public InfoState {
    entries = entries == null ? Map.of() : Map.copyOf(entries);
    documents = documents == null ? Map.of() : Map.copyOf(documents);
    contacts = contacts == null ? Map.of() : Map.copyOf(contacts);
    entries.forEach((key, entry) -> {
      if (!key.equals(entry.key())) throw new IllegalArgumentException("INFO 键与条目不一致");
    });
  }

  public record Entry(String pack, String id, String title, String text, String type, JsonNode payload,
                      List<String> subjects, List<String> audience, Evidence evidence,
                      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                      List<String> decisionAudience) {
    public Entry(String pack, String id, String title, String text, String type, JsonNode payload,
                 List<String> subjects, List<String> audience, Evidence evidence) {
      this(pack, id, title, text, type, payload, subjects, audience, evidence, null);
    }
    public Entry {
      NodeAddress.requireSegment(pack);
      NodeAddress.requireSegment(id);
      if (title == null || title.isBlank() || (text == null && payload == null)) throw new IllegalArgumentException("INFO 须有标题和内容");
      if (payload != null && (type == null || type.isBlank() || payload.isNull())) throw new IllegalArgumentException("INFO 数据需要类型");
      payload = payload == null ? null : payload.deepCopy();
      subjects = subjects == null ? List.of() : List.copyOf(subjects);
      subjects.forEach(InfoState::requirePath);
      audience = audience == null ? List.of() : audience.stream().distinct().sorted().toList();
      audience.forEach(NodeAddress::requireSegment);
      decisionAudience = decisionAudience == null ? List.of() : decisionAudience.stream().distinct().sorted().toList();
      decisionAudience.forEach(NodeAddress::requireSegment);
      if (evidence == null) throw new IllegalArgumentException("INFO 需要证据状态");
    }

    @Override public JsonNode payload() { return payload == null ? null : payload.deepCopy(); }
    public String key() { return pack + "/" + id; }
  }

  public record Document(String id, String countryId, String title, String text, String scope,
                         List<String> countries, Evidence evidence) {
    public Document {
      NodeAddress.requireSegment(id);
      NodeAddress.requireSegment(countryId);
      requireContent(title, text);
      countries = ids(countries);
      if (!java.util.Set.of("core", "public", "shared").contains(scope)
          || scope.equals("shared") != !countries.isEmpty() || evidence == null) {
        throw new IllegalArgumentException("国家情报文档范围无效");
      }
    }
  }

  public record Contact(String id, String owner, String title, String provinceId, String observedAt,
                        String suspectedUnit, String text, List<String> recipients, Evidence evidence) {
    public Contact {
      NodeAddress.requireSegment(id);
      NodeAddress.requireSegment(owner);
      NodeAddress.requireSegment(provinceId);
      observedAt = io.mosire.worldsim.timeline.SimulationTime.normalize(observedAt);
      requireContent(title, text);
      recipients = ids(recipients);
      if (evidence == null || suspectedUnit != null && suspectedUnit.length() > 512) {
        throw new IllegalArgumentException("情报标记需要有效证据");
      }
    }
  }

  public record ContactUpdate(String id, Contact contact) {
    public ContactUpdate {
      NodeAddress.requireSegment(id);
      if (contact != null && !id.equals(contact.id())) throw new IllegalArgumentException("情报标记 ID 不一致");
    }
  }

  private static void requireContent(String title, String text) {
    if (title == null || title.isBlank() || title.length() > 240 || text == null || text.isBlank() || text.length() > 32000) {
      throw new IllegalArgumentException("需要有效的标题和正文");
    }
  }

  static List<String> ids(List<String> ids) {
    if (ids == null) return List.of();
    ids.forEach(NodeAddress::requireSegment);
    return ids.stream().distinct().sorted().toList();
  }

  public InfoState with(Document document) {
    if (document.scope().equals("core") && documents.values().stream().anyMatch(item ->
        !item.id().equals(document.id()) && item.scope().equals("core") && item.countryId().equals(document.countryId()))) {
      throw new IllegalArgumentException("一个国家只能有一份核心情报文档");
    }
    var next = new TreeMap<>(documents);
    next.put(document.id(), document);
    return new InfoState(entries, next, contacts);
  }

  public InfoState with(ContactUpdate update, String owner) {
    var next = new TreeMap<>(contacts);
    var old = next.get(update.id());
    if (!"gm".equals(owner) && old != null && !old.owner().equals(owner)
        || update.contact() != null && !update.contact().owner().equals(owner)) {
      throw new IllegalArgumentException("情报标记须由原发布者维护");
    }
    if (update.contact() == null) {
      if (old == null) throw new IllegalArgumentException("情报标记不存在");
      next.remove(update.id());
    } else {
      if (old != null && !old.owner().equals(owner)) throw new IllegalArgumentException("情报标记已由其他身份占用");
      next.put(update.id(), update.contact());
    }
    return new InfoState(entries, documents, next);
  }

  public InfoState with(Entry entry) {
    var next = new TreeMap<>(entries);
    next.put(entry.key(), entry);
    return new InfoState(next, documents, contacts);
  }

  public static String requirePath(String path) {
    if (path == null) throw new IllegalArgumentException("需要实体路径");
    return String.join("/", NodeAddress.parse("main-0/" + path).requireEntityPath().path());
  }
}
