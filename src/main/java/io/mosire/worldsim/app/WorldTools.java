package io.mosire.worldsim.app;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** AgentLib tool declarations; calls must still pass through ToolCallAuthorizer. */
public final class WorldTools {
  private WorldTools() {}

  public static ToolRegistry create(WorldApplicationService app) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(tool(app, "world_overview", "读取世界或指定节点的摘要", false,
        props("node", string()), List.of(), (a, c) -> app.overview(optionalString(a, "node"))));
    tools.register(tool(app, "world_lookup", "按节点地址读取地图、国家或编制实体；地图查询默认省略大型几何", false,
        props("address", string(), "includeGeometry", bool()), List.of("address"),
        (a, c) -> app.lookup(requiredString(a, "address"), optionalBool(a, "includeGeometry", false))));
    tools.register(tool(app, "world_search", "按名称、别名或 ID 查当前或指定保存节点的实体", false,
        props("node", string(), "query", string(), "kind", string(), "offset", integer(), "limit", integer()),
        List.of("query"), (a, c) -> app.search(optionalString(a, "node"), requiredString(a, "query"),
            optionalString(a, "kind"), optionalInt(a, "offset", 0), optionalInt(a, "limit", 25))));
    tools.register(tool(app, "orbat_export", "从编制文档或节点地址导出 DSH mil-org v1，并报告损失", false,
        props("address", string()), List.of("address"), (a, c) -> app.exportMilOrg(requiredString(a, "address"))));
    tools.register(tool(app, "world_validate", "校验节点并列出遗留拓扑差异", false,
        props("node", string()), List.of("node"), (a, c) -> app.validate(requiredString(a, "node"))));
    tools.register(tool(app, "timeline_tree", "分页列出分支与节点", false,
        props("offset", integer(), "limit", integer()), List.of(),
        (a, c) -> app.tree(optionalInt(a, "offset", 0), optionalInt(a, "limit", 100))));
    tools.register(tool(app, "timeline_diff", "查看从祖先节点到后代节点的增量摘要", false,
        props("from", string(), "to", string(), "offset", integer(), "limit", integer()), List.of("from", "to"),
        (a, c) -> app.diff(requiredString(a, "from"), requiredString(a, "to"), optionalInt(a, "offset", 0), optionalInt(a, "limit", 100))));
    tools.register(tool(app, "timeline_verify", "完整验证所有已保存节点、增量和检查点", false,
        props(), List.of(), (a, c) -> app.verifyTimeline()));
    tools.register(tool(app, "edit_open", "从已保存节点打开推进或 fork 工作副本", true,
        props("base", string(), "expectedHash", string(), "forkBranch", string()), List.of("base", "expectedHash"),
        (a, c) -> app.open(actor(c), requiredString(a, "base"), requiredString(a, "expectedHash"), optionalString(a, "forkBranch"))));
    tools.register(tool(app, "edit_apply", "原子批量预览或修改地图、国家、编制和部署", true,
        props("draftId", string(), "expectedVersion", integer(), "requestId", string(), "commands",
            Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", commandSchema()), "dryRun", bool()),
        List.of("draftId", "expectedVersion", "requestId", "commands"),
        (a, c) -> app.apply(actor(c), requiredString(a, "draftId"), requiredLong(a, "expectedVersion"),
            requiredString(a, "requestId"), commands(a), optionalBool(a, "dryRun", false))));
    tools.register(tool(app, "edit_save", "保存草稿为新节点；requestId 可在重启后幂等重试", true,
        props("draftId", string(), "expectedVersion", integer(), "requestId", string(), "tick", integer(), "baseHash", string()),
        List.of("draftId", "expectedVersion", "requestId", "tick", "baseHash"),
        (a, c) -> app.save(actor(c), requiredString(a, "draftId"), requiredLong(a, "expectedVersion"),
            requiredString(a, "requestId"), requiredLong(a, "tick"), requiredString(a, "baseHash"))));
    tools.register(tool(app, "edit_discard", "丢弃内存工作副本", true,
        props("draftId", string(), "expectedVersion", integer()), List.of("draftId", "expectedVersion"),
        (a, c) -> app.discard(actor(c), requiredString(a, "draftId"), requiredLong(a, "expectedVersion"))));
    tools.register(tool(app, "world_export", "将全部已提交分支导出为自包含目录", true,
        props(), List.of(), (a, c) -> app.exportWorld()));
    tools.register(tool(app, "world_view", "为已保存节点重建可读视图", true,
        props("node", string()), List.of("node"), (a, c) -> app.refreshView(requiredString(a, "node"))));
    return tools;
  }

  private static AgentTool tool(WorldApplicationService app, String name, String description, boolean write,
      Map<String, Object> properties, List<String> required,
      BiFunction<Map<String, Object>, ToolContext, Map<String, Object>> call) {
    return new AgentTool() {
      @Override public String name() { return name; }
      @Override public String description() { return description; }
      @Override public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
      }
      @Override public Set<String> roles() { return Set.of("GM"); }
      @Override public ToolSpec spec() { return ToolSpec.level(AccessToken.DEFAULT, false, write); }
      @Override public ResourceManifest resources() { return ResourceManifest.of("world", ResourcePolicy.DENY); }
      @Override public ToolResult execute(ToolContext context) {
        try {
          context.resources().require(write ? Operation.WRITE : Operation.READ, ResourceId.of("world", "/" + app.worldId()));
          Map<String, Object> arguments = context.arguments();
          for (String key : arguments.keySet()) {
            if (!properties.containsKey(key)) throw new TimelineException("ARGUMENT_INVALID", "未知参数: " + key);
          }
          for (String key : required) {
            if (!arguments.containsKey(key) || arguments.get(key) == null) throw new TimelineException("ARGUMENT_INVALID", "缺少参数: " + key);
          }
          return ToolResult.ok(JsonCodec.canonicalJson(call.apply(arguments, context)));
        } catch (TimelineException e) {
          return ToolResult.error(e.code(), e.getMessage());
        } catch (AddressException e) {
          return ToolResult.error(e.code(), e.getMessage());
        } catch (IllegalArgumentException | ClassCastException e) {
          return ToolResult.error("ARGUMENT_INVALID", e.getMessage() == null ? "参数类型不正确" : e.getMessage());
        }
      }
    };
  }

  private static Map<String, Object> props(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
    return map;
  }
  private static Map<String, Object> string() { return Map.of("type", "string", "minLength", 1, "maxLength", 512); }
  private static Map<String, Object> integer() { return Map.of("type", "integer", "minimum", 0); }
  private static Map<String, Object> bool() { return Map.of("type", "boolean"); }

  private static Map<String, Object> commandSchema() {
    Map<String, Object> number = Map.of("type", "number");
    Map<String, Object> point = object(props("x", number, "y", number), "x", "y");
    Map<String, Object> ring = object(props("points", array(point)), "points");
    Map<String, Object> part = object(props("outer", ring, "holes", array(ring)), "outer", "holes");
    Map<String, Object> shape = object(props("parts", array(part)), "parts");
    Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
    Map<String, Object> nullableNumber = Map.of("type", List.of("number", "null"));
    Map<String, Object> province = object(props("id", string(), "name", string(), "kind", string(),
        "terrain", string(), "shape", shape, "displayPoint", point, "stateId", nullableString,
        "regionId", nullableString, "tags", array(string())),
        "id", "name", "kind", "terrain", "shape", "displayPoint", "tags");
    Map<String, Object> region = object(props("id", string(), "name", string(), "provinceIds", array(string())), "id", "name", "provinceIds");
    Map<String, Object> hub = object(props("id", string(), "provinceId", string(), "level", integer(),
        "capacity", nullableNumber, "range", nullableNumber), "id", "provinceId", "level");
    Map<String, Object> rail = object(props("id", string(), "level", integer(), "provincePath", array(string())), "id", "level", "provincePath");
    Map<String, Object> link = object(props("key", string(), "category", string(), "targetId", string(),
        "displayPoint", Map.of("anyOf", List.of(point, Map.of("type", "null"))), "tags", array(string())),
        "key", "category", "targetId", "tags");
    Map<String, Object> adjacency = object(props("a", string(), "b", string(), "kind", string(),
        "borderLength", number, "through", nullableString), "a", "b", "kind", "borderLength");
    Map<String, Object> evidence = object(props("source", nullableString, "status", string(),
        "confidence", nullableNumber, "date", nullableString), "status");
    Map<String, Object> country = object(props("id", string(), "name", string(), "aliases", array(string()),
        "color", nullableString, "capitalProvinceId", nullableString, "evidence", evidence), "id", "name", "evidence");
    Map<String, Object> assignment = object(props("countryId", string(), "evidence", evidence), "countryId", "evidence");
    Map<String, Object> ref = object(props("documentId", string(), "nodeId", string(), "contentHash", nullableString),
        "documentId", "nodeId");
    Map<String, Object> unit = object(props("id", string(), "name", string(), "type", nullableString,
        "countryId", nullableString, "provinceId", nullableString,
        "idealRef", Map.of("anyOf", List.of(ref, Map.of("type", "null"))),
        "actualRef", Map.of("anyOf", List.of(ref, Map.of("type", "null"))),
        "evidence", evidence, "deploymentEvidence", evidence), "id", "name", "evidence");
    Map<String, Object> document = object(props("format", string(), "documentId", string(), "role", string(),
        "date", nullableString, "root", Map.of("type", "object", "required", List.of("nodeId", "name"),
            "description", "orbat-native-v1 递归节点，字段含 nodeId、name、type、echelon、personnel、equipment、children、extensions")),
        "format", "documentId", "role", "root");
    return Map.of("type", "object", "required", List.of("op"), "additionalProperties", true,
        "description", "地图操作用相应实体或 ID。setTerritory 用 provinceIds、field 和 assignment；deployUnit 用 unitId、provinceId 和 evidence。可组合多个命令维护引用一致性。",
        "properties", props("op", Map.of("type", "string", "enum", List.of("patchProvince", "rebuildAdjacencies", "putProvince", "removeProvince",
                "putState", "removeState", "putStrategicRegion", "removeStrategicRegion", "putSupplyHub", "removeSupplyHub",
                "putRailway", "removeRailway", "putEntityLink", "removeEntityLink", "replaceAdjacencies",
                "putCountry", "setTerritory", "putOrbatDocument", "importMilOrg", "putUnit", "deployUnit")),
            "id", string(), "name", string(), "kind", string(), "terrain", string(), "shape", shape,
            "displayPoint", point, "stateId", nullableString, "regionId", nullableString, "tags", array(string()),
            "province", province, "provinceId", nullableString, "state", region, "region", region,
            "supplyHub", hub, "supplyHubId", string(), "railway", rail, "railwayId", string(),
            "entityLink", link, "key", string(), "adjacencies", array(adjacency),
            "country", country, "provinceIds", array(string()), "field", string(),
            "assignment", Map.of("anyOf", List.of(assignment, Map.of("type", "null"))),
            "document", document, "documentId", string(), "role", string(), "source", string(),
            "milOrg", Map.of("type", "object"), "unit", unit, "unitId", string(), "evidence", evidence));
  }

  private static Map<String, Object> array(Map<String, Object> item) { return Map.of("type", "array", "items", item); }
  private static Map<String, Object> object(Map<String, Object> properties, String... required) {
    return Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
  }
  private static String actor(ToolContext context) { return "gm:" + context.identity().instanceId(); }
  private static String requiredString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (!(value instanceof String s) || s.isBlank() || s.length() > 512) throw new IllegalArgumentException(key + " 必须是非空字符串");
    return s;
  }
  private static String optionalString(Map<String, Object> args, String key) {
    return args.containsKey(key) ? requiredString(args, key) : null;
  }
  private static boolean optionalBool(Map<String, Object> args, String key, boolean fallback) {
    Object value = args.get(key);
    if (value == null && !args.containsKey(key)) return fallback;
    if (!(value instanceof Boolean b)) throw new IllegalArgumentException(key + " 必须是布尔值");
    return b;
  }
  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
      throw new IllegalArgumentException(key + " 必须是非负整数");
    }
    return ((Number) value).longValue();
  }
  private static long optionalLong(Map<String, Object> args, String key, long fallback) {
    return args.containsKey(key) ? requiredLong(args, key) : fallback;
  }
  private static int optionalInt(Map<String, Object> args, String key, int fallback) {
    long value = optionalLong(args, key, fallback);
    if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " 超出范围");
    return (int) value;
  }
  private static List<Map<String, Object>> commands(Map<String, Object> args) {
    Object value = args.get("commands");
    if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 100) throw new IllegalArgumentException("commands 必须有 1..100 项");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("每项 command 必须是对象");
      Map<String, Object> typed = new LinkedHashMap<>();
      for (var entry : raw.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("command key 必须是字符串");
        typed.put(key, entry.getValue());
      }
      result.add(typed);
    }
    return result;
  }
}
