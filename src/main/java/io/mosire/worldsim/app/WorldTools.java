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
    tools.register(tool(app, "world_population", "按日期和口径读取省人口，或按州、国家实控范围、全图统计覆盖", false,
        props("node", string(), "date", string(), "basis", string(), "scope", string()), List.of("date", "basis"),
        (a, c) -> app.population(optionalString(a, "node"), requiredString(a, "date"), requiredString(a, "basis"), optionalString(a, "scope"))));
    tools.register(tool(app, "world_info", "读取保存节点上的 INFO", false,
        props("node", string()), List.of(), (a, c) -> app.info(optionalString(a, "node"))));
    tools.register(tool(app, "world_turn", "查看开放回合的收件或已结算的决策与裁决", false,
        props("address", string()), List.of("address"), (a, c) -> app.turn(requiredString(a, "address"))));
    tools.register(tool(app, "world_geo_lookup", "用地名或 WGS84 经纬度查省，或用完整省份地址查近似 WGS84 位置", false,
        props("node", string(), "name", string(), "longitude", Map.of("type", "number"),
            "latitude", Map.of("type", "number"), "address", string()), List.of(),
        (a, c) -> app.geoLookup(optionalString(a, "node"), optionalString(a, "name"),
            optionalNumber(a, "longitude"), optionalNumber(a, "latitude"), optionalString(a, "address"))));
    tools.register(tool(app, "world_territory_gaps", "审计陆省归属漏填和冲突；返回覆盖范围。可用 countryId 和 expectedProvinceIds 核对任务省清单",
        false, props("node", string(), "scope", string(), "countryId", string(), "stateId", string(),
            "offset", integer(), "limit", integer(), "expectedProvinceIds", array(string())), List.of(),
        (a, c) -> app.territoryGaps(optionalString(a, "node"), optionalString(a, "scope"),
            optionalString(a, "countryId"), optionalString(a, "stateId"), optionalInt(a, "offset", 0), optionalInt(a, "limit", 25),
            a.containsKey("expectedProvinceIds") ? addresses(a, "expectedProvinceIds") : null)));
    tools.register(tool(app, "orbat_export", "从编制文档或节点地址导出 DSH mil-org v1，并报告损失", false,
        props("address", string()), List.of("address"), (a, c) -> app.exportMilOrg(requiredString(a, "address"))));
    tools.register(tool(app, "orbat_inventory", "按单位汇总实际编制，或读取实编节点、机关库存的直属装备。details 可列出各持有节点", false,
        props("address", string(), "details", bool()), List.of("address"),
        (a, c) -> app.inventory(requiredString(a, "address"), optionalBool(a, "details", false))));
    tools.register(tool(app, "world_rail_capacity", "按国家地址读取经济期和军用额度。可给同节点铁路省地址序列及装备 itemId，查看路线余量和可运件数", false,
        props("countryAddress", string(), "route", array(string()), "itemId", string()), List.of("countryAddress"),
        (a, c) -> app.railCapacity(requiredString(a, "countryAddress"), route(a), optionalString(a, "itemId"))));
    tools.register(economyTool(app, "orbat_deduct_equipment", "扣减实际编制装备。指定 formationAddress 扣该节点直属库存，省略时在整棵实编子树均摊",
        "deductEquipment", props("unitAddress", string(), "formationAddress", string(), "itemId", string(), "count", positiveInteger(), "evidence", evidenceSchema()),
        List.of("unitAddress", "itemId", "count", "evidence")));
    tools.register(economyTool(app, "orbat_transfer_equipment", "结算机关库存向本国单位的交付。跨省须给完整铁路省地址序列，同时扣当期运力",
        "transferEquipment", props("depotAddress", string(), "unitAddress", string(), "formationAddress", string(),
            "itemId", string(), "count", positiveInteger(), "route", array(string()), "evidence", evidenceSchema()),
        List.of("depotAddress", "unitAddress", "itemId", "count", "evidence")));
    tools.register(economyTool(app, "orbat_reinforce_personnel", "从所属国家人力池补员，写入实际编制根或指定下属节点",
        "reinforcePersonnel", props("countryAddress", string(), "unitAddress", string(), "formationAddress", string(),
            "count", positiveInteger(), "evidence", evidenceSchema()), List.of("countryAddress", "unitAddress", "count", "evidence")));
    tools.register(tool(app, "world_movement_estimate", "按编制最低速度和陆上省链估算行军耗时，省略路线时只查速度", false,
        props("address", string(), "role", Map.of("type", "string", "enum", List.of("actual", "ideal")),
            "route", Map.of("type", "array", "items", string(), "minItems", 2,
                "description", "同一节点下的陆地省份地址序列，第一项是单位的部署省")),
        List.of("address"), (a, c) -> app.movementEstimate(requiredString(a, "address"),
            optionalString(a, "role"), route(a))));
    tools.register(tool(app, "world_plan_land_route", "在已配准且获准通行的陆省之间规划行军",
        false, props("address", string(), "destination", string(), "via", array(string())), List.of("address", "destination"),
        (a, c) -> app.landRoute(requiredString(a, "address"), requiredString(a, "destination"),
            a.containsKey("via") ? addresses(a, "via") : List.of())));
    tools.register(tool(app, "world_marches", "读取保存节点的行军路线、位置、进度和剧本时间", false,
        props("node", string()), List.of(), (a, c) -> app.marches(app.savedMap(optionalString(a, "node")))));
    tools.register(tool(app, "world_supply_status", "按单位地址读取首都、铁路及补给中心的可达状态", false,
        props("address", string()), List.of("address"), (a, c) -> app.supplyStatus(requiredString(a, "address"))));
    tools.register(tool(app, "world_battles", "分页读取保存节点的战斗、损耗、控制变化和证据", false,
        props("node", string(), "offset", integer(), "limit", integer()), List.of(),
        (a, c) -> app.battles(app.savedMap(optionalString(a, "node")), optionalInt(a, "offset", 0), optionalInt(a, "limit", 25))));
    tools.register(tool(app, "world_battle_context", "核对交战省和参战地点，读取双方地形、跨河、铁路与补给条件", false,
        props("provinceAddress", string(), "attackers", array(string()), "defenders", array(string())),
        List.of("provinceAddress", "attackers", "defenders"), (a, c) -> app.battleContext(requiredString(a, "provinceAddress"),
            addresses(a, "attackers"), addresses(a, "defenders"))));
    tools.register(tool(app, "world_validate", "校验节点并列出遗留拓扑差异", false,
        props("node", string()), List.of("node"), (a, c) -> app.validate(requiredString(a, "node"))));
    tools.register(tool(app, "timeline_tree", "分页列出分支与节点", false,
        props("offset", integer(), "limit", integer()), List.of(),
        (a, c) -> app.tree(optionalInt(a, "offset", 0), optionalInt(a, "limit", 100))));
    tools.register(tool(app, "timeline_lineage", "按父链读分支的线性时间线和剧本时间", false,
        props("branch", string()), List.of(), (a, c) -> app.lineage(optionalString(a, "branch"))));
    tools.register(tool(app, "timeline_diff", "查看从祖先节点到后代节点的增量摘要", false,
        props("from", string(), "to", string(), "offset", integer(), "limit", integer()), List.of("from", "to"),
        (a, c) -> app.diff(requiredString(a, "from"), requiredString(a, "to"), optionalInt(a, "offset", 0), optionalInt(a, "limit", 100))));
    tools.register(tool(app, "timeline_verify", "完整验证所有已保存节点、增量和检查点", false,
        props(), List.of(), (a, c) -> app.verifyTimeline()));
    tools.register(tool(app, "edit_open", "从已保存节点打开推进或 fork 工作副本，可启用事件时间线", true,
        props("base", string(), "expectedHash", string(), "forkBranch", string(), "eventTimeline", bool()), List.of("base", "expectedHash"),
        (a, c) -> app.open(actor(c), requiredString(a, "base"), requiredString(a, "expectedHash"),
            optionalString(a, "forkBranch"), optionalBool(a, "eventTimeline", false))));
    tools.register(tool(app, "edit_apply", "原子批量预览或修改地图、国家、编制、部署、剧本时钟、行军及军事经济", true,
        props("draftId", string(), "expectedVersion", integer(), "requestId", string(), "commands",
            Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", commandSchema()), "dryRun", bool()),
        List.of("draftId", "expectedVersion", "requestId", "commands"),
        (a, c) -> app.apply(actor(c), requiredString(a, "draftId"), requiredLong(a, "expectedVersion"),
            requiredString(a, "requestId"), commands(a), optionalBool(a, "dryRun", false))));
    tools.register(tool(app, "edit_save", "发布草稿；事件时间线按时刻保存并自动分配 tick，旧式编辑仍需 tick", true,
        props("draftId", string(), "expectedVersion", integer(), "requestId", string(), "tick", integer(), "baseHash", string()),
        List.of("draftId", "expectedVersion", "requestId", "baseHash"),
        (a, c) -> app.save(actor(c), requiredString(a, "draftId"), requiredLong(a, "expectedVersion"),
            requiredString(a, "requestId"), a.containsKey("tick") ? Long.valueOf(requiredLong(a, "tick")) : null,
            requiredString(a, "baseHash"))));
    tools.register(tool(app, "edit_discard", "丢弃内存工作副本", true,
        props("draftId", string(), "expectedVersion", integer()), List.of("draftId", "expectedVersion"),
        (a, c) -> app.discard(actor(c), requiredString(a, "draftId"), requiredLong(a, "expectedVersion"))));
    tools.register(tool(app, "world_export", "将全部已提交分支导出为自包含目录", true,
        props(), List.of(), (a, c) -> app.exportWorld()));
    tools.register(tool(app, "world_view", "为已保存节点重建可读视图", true,
        props("node", string()), List.of("node"), (a, c) -> app.refreshView(requiredString(a, "node"))));
    return tools;
  }

  static ToolRegistry decisions(WorldApplicationService app, WorldTurn.DecisionSession session) {
    ToolRegistry tools = new ToolRegistry();
    String role = "DECISION";
    tools.register(tool(app, role, "decision_brief", "读取绑定回合的授权简报、基点和可查询地址", false,
        props(), List.of(), (a, c) -> app.decisionBrief(session)));
    tools.register(tool(app, role, "decision_lookup", "读取简报授权的同节点实体地址", false,
        props("address", string()), List.of("address"), (a, c) -> app.decisionLookup(session, requiredString(a, "address"))));
    if (session.scoped()) tools.register(tool(app, role, "decision_markers", "列出本身份可见的档案标记、友军和地图设施", false,
        props(), List.of(), (a, c) -> app.decisionMarkers(session)));
    if (session.scoped()) tools.register(tool(app, role, "decision_search", "按本身份的可见面查名称与地址", false,
        props("query", string(), "limit", integer()), List.of("query"), (a, c) ->
            app.decisionSearch(session, requiredString(a, "query"), optionalInt(a, "limit", 25))));
    tools.register(tool(app, role, "decision_submit", "提交本身份的决策正文、理由及可选的授权目标地址", true,
        props("text", Map.of("type", "string", "maxLength", 32000), "rationale", Map.of("type", "string", "maxLength", 16000),
            "targets", array(string()),
            "movements", array(object(props("unitId", string(), "destinationId", string(), "via", array(string())), "unitId", "destinationId")),
            "reports", array(object(props("key", string(), "retract", bool(), "title", string(), "provinceId", string(),
                "observedAt", string(), "suspectedUnit", string(), "text", string(), "recipients", array(string()),
                "evidence", evidenceSchema()), "key"))), List.of("text", "rationale"),
        (a, c) -> app.submitDecision(session, decisionText(a, "text"), decisionText(a, "rationale"),
            a.containsKey("targets") ? addresses(a, "targets") : List.of(),
            optionalRecords(a, "movements", io.mosire.worldsim.info.TurnState.Movement.class),
            optionalRecords(a, "reports", WorldTurn.ContactDraft.class))));
    return tools;
  }

  private static <T> List<T> optionalRecords(Map<String, Object> args, String key, Class<T> type) {
    if (!args.containsKey(key)) return List.of();
    if (!(args.get(key) instanceof List<?> entries) || entries.size() > 100) {
      throw new IllegalArgumentException(key + " 须为最多 100 条记录的数组");
    }
    return entries.stream().map(item -> java.util.Objects.requireNonNull(
        io.mosire.worldsim.json.JsonCodec.mapper().convertValue(item, type), "需要记录")).toList();
  }

  private static String decisionText(Map<String, Object> input, String key) {
    if (!(input.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("需要文本字段: " + key);
    return text;
  }

  private static AgentTool tool(WorldApplicationService app, String name, String description, boolean write,
      Map<String, Object> properties, List<String> required,
      BiFunction<Map<String, Object>, ToolContext, ?> call) {
    return tool(app, "GM", name, description, write, properties, required, call);
  }

  private static AgentTool tool(WorldApplicationService app, String role, String name, String description, boolean write,
      Map<String, Object> properties, List<String> required, BiFunction<Map<String, Object>, ToolContext, ?> call) {
    return new AgentTool() {
      @Override public String name() { return name; }
      @Override public String description() { return description; }
      @Override public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
      }
      @Override public Set<String> roles() { return Set.of(role); }
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

  private static AgentTool economyTool(WorldApplicationService app, String name, String description, String op,
                                      Map<String, Object> command, List<String> required) {
    var draftKeys = List.of("draftId", "expectedVersion", "requestId");
    var args = new LinkedHashMap<>(command);
    args.putAll(props("draftId", string(), "expectedVersion", integer(), "requestId", string(), "dryRun", bool()));
    var requiredArguments = new ArrayList<>(draftKeys);
    requiredArguments.addAll(required);
    return tool(app, name, description, true, args, requiredArguments, (input, caller) -> {
      var fields = new LinkedHashMap<>(input);
      draftKeys.forEach(fields::remove);
      fields.remove("dryRun");
      fields.put("op", op);
      return app.apply(actor(caller), requiredString(input, "draftId"), requiredLong(input, "expectedVersion"),
          requiredString(input, "requestId"), List.of(fields), optionalBool(input, "dryRun", false));
    });
  }

  private static Map<String, Object> props(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
    return map;
  }
  private static Map<String, Object> string() { return Map.of("type", "string", "minLength", 1, "maxLength", 512); }
  private static Map<String, Object> integer() { return Map.of("type", "integer", "minimum", 0); }
  private static Map<String, Object> positiveInteger() { return Map.of("type", "integer", "minimum", 1, "maximum", Integer.MAX_VALUE); }
  private static Map<String, Object> bool() { return Map.of("type", "boolean"); }

  private static Map<String, Object> evidenceSchema() {
    return object(props("source", string(), "status", Map.of("type", "string", "enum", List.of("known", "estimated", "inferred")),
        "confidence", Map.of("type", List.of("number", "null"), "minimum", 0, "maximum", 1),
        "date", Map.of("type", List.of("string", "null"))), "source", "status");
  }

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
    Map<String, Object> evidence = object(props("source", nullableString, "status", string(),
        "confidence", nullableNumber, "date", nullableString), "status");
    Map<String, Object> railOperation = object(props(
        "status", Map.of("type", "string", "enum", List.of("open", "closed", "unknown")),
        "tonnesPerDay", nullableNumber, "evidence", evidence), "status", "evidence");
    Map<String, Object> rail = object(props("id", string(), "level", integer(), "provincePath", array(string()),
        "operation", Map.of("anyOf", List.of(railOperation, Map.of("type", "null")))), "id", "level", "provincePath");
    Map<String, Object> link = object(props("key", string(), "category", string(), "targetId", string(),
        "displayPoint", Map.of("anyOf", List.of(point, Map.of("type", "null"))), "tags", array(string())),
        "key", "category", "targetId", "tags");
    Map<String, Object> adjacency = object(props("a", string(), "b", string(), "kind", string(),
        "borderLength", number, "through", nullableString), "a", "b", "kind", "borderLength");
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
            "description", "orbat-native-v1 递归节点，字段含 nodeId、name、type、echelon、personnel、equipment、children、extensions、marchSpeed（kmPerHour 与 evidence）")),
        "format", "documentId", "role", "root");
    Map<String, Object> depot = object(props("id", string(), "name", string(), "countryId", string(), "provinceId", string(),
        "equipment", Map.of("type", "object", "description", "ORBAT 库存，含 coverage、items 和 evidence"),
        "evidence", evidenceSchema()), "id", "name", "countryId", "provinceId", "evidence");
    Map<String, Object> kilograms = object(props("kilograms", integer(), "evidence", evidenceSchema()), "kilograms", "evidence");
    Map<String, Object> quantityMap = Map.of("type", "object", "additionalProperties", kilograms);
    Map<String, Object> railRules = object(props("periodMinutes", positiveInteger(), "countryQuotas", quantityMap, "itemWeights", quantityMap), "periodMinutes");
    return Map.of("type", "object", "required", List.of("op"), "additionalProperties", true,
        "description", "地图操作用相应实体或 ID。setSimulationTime 用 time，advanceTime 用正整数 minutes 或 ISO 日期时间 to，startMarch 用单位 address 和包含起点的省份 route，cancelMarch 用单位 address。地址须属于草稿基点。",
        "properties", props("op", Map.of("type", "string", "enum", List.of("patchProvince", "rebuildAdjacencies", "putProvince", "removeProvince",
                "putState", "removeState", "putStrategicRegion", "removeStrategicRegion", "putSupplyHub", "removeSupplyHub",
                "putRailway", "removeRailway", "putEntityLink", "removeEntityLink", "replaceAdjacencies",
                "putCountry", "setTerritory", "putOrbatDocument", "importMilOrg", "putUnit", "deployUnit",
                "setSimulationTime", "advanceTime", "startMarch", "cancelMarch", "resolveBattle", "setTransitRight", "setRiverCrossing",
                "putCommandDepot", "configureRailEconomy", "setManpowerPool", "deductEquipment", "transferEquipment", "reinforcePersonnel",
                "setPolitics", "putCensus", "putTreaty", "putInfo", "openTurn", "resolveTurn", "putMapFeature", "putHeadquarters", "putIntelligenceDocument", "putContact", "retractContact")),
            "id", string(), "name", string(), "kind", string(), "terrain", string(), "shape", shape,
            "displayPoint", point, "stateId", nullableString, "regionId", nullableString, "tags", array(string()),
            "province", province, "provinceId", nullableString, "state", region, "region", region,
            "supplyHub", hub, "supplyHubId", string(), "railway", rail, "railwayId", string(),
            "entityLink", link, "key", string(), "adjacencies", array(adjacency),
            "country", country, "provinceIds", array(string()), "field", string(),
            "assignment", Map.of("anyOf", List.of(assignment, Map.of("type", "null"))),
            "document", document, "documentId", string(), "role", string(), "source", string(),
            "milOrg", Map.of("type", "object"), "unit", unit, "unitId", string(),
            "evidence", Map.of("anyOf", List.of(evidence, Map.of("type", "null"))),
            "grantorAddress", string(), "guestAddress", string(),
            "fromAddress", string(), "toAddress", string(), "crossesRiver", Map.of("type", List.of("boolean", "null")),
            "time", string(), "minutes", Map.of("type", "integer", "minimum", 1), "to", string(), "address", string(), "route", array(string()),
            "depot", depot, "rules", railRules, "available", integer(), "depotAddress", string(), "countryAddress", string(),
            "unitAddress", string(), "formationAddress", string(), "itemId", string(), "count", positiveInteger(),
            "countryId", string(), "politics", object(props("regime", string(), "government", string(), "mobilization", string(), "evidence", evidence), "regime", "government", "mobilization", "evidence"),
            "records", array(object(props("provinceId", string(), "date", string(), "basis", string(), "people", integer(), "evidence", evidence),
                "provinceId", "date", "basis", "people", "evidence")),
            "treaty", object(props("id", string(), "first", string(), "second", string(),
                "type", Map.of("enum", List.of("war", "ceasefire", "alliance", "transit", "agreement")),
                "startsAt", string(), "endsAt", nullableString, "terms", Map.of("type", "string"), "evidence", evidence),
                "id", "first", "second", "type", "startsAt", "terms", "evidence"),
            "feature", object(props("id", string(), "name", string(), "kind", string(), "provinceId", string(),
                "waterProvinceId", string(), "level", integer(), "operation", string(), "commodity", string(),
                "quantity", Map.of("type", "number"), "unit", string(), "evidence", evidence), "id", "name", "kind", "provinceId", "evidence"),
            "headquarters", object(props("id", string(), "name", string(), "kind", string(), "countryId", string(),
                "parentId", string(), "depotId", string(), "unitIds", array(string())), "id", "name", "kind", "countryId"),
            "intelligenceDocument", object(props("id", string(), "countryId", string(), "title", string(), "text", string(),
                "scope", string(), "countries", array(string()), "evidence", evidence), "id", "countryId", "title", "text", "scope", "evidence"),
            "contact", object(props("id", string(), "owner", string(), "title", string(), "provinceId", string(), "observedAt", string(),
                "suspectedUnit", string(), "text", string(), "recipients", array(string()), "evidence", evidence),
                "id", "owner", "title", "provinceId", "observedAt", "text", "evidence"),
            "entry", object(props("pack", string(), "id", string(), "title", string(), "text", Map.of("type", "string"),
                "type", string(), "payload", Map.of(), "subjects", array(string()), "audience", array(string()), "decisionAudience", array(string()), "evidence", evidence),
                "pack", "id", "title", "evidence"),
            "brief", object(props("id", string(), "scoped", bool(), "access", Map.of("type", "object", "additionalProperties", array(string())),
                "participants", Map.of("type", "object", "additionalProperties", object(props("displayName", string(),
                    "subject", object(props("kind", Map.of("enum", List.of("country", "orbat/unit", "orbat/command")), "id", string()), "kind", "id"),
                    "readablePaths", array(string()), "proposalTargets", array(string()), "contactRecipients", array(string())),
                    "displayName", "subject", "readablePaths", "proposalTargets"))), "id"),
            "turnAddress", string(), "resolution", Map.of("type", "string"),
            "rulings", array(object(props("id", string(), "title", string(), "text", string(),
                "decisionMakerId", string(), "disposition", Map.of("enum", io.mosire.worldsim.info.TurnState.DISPOSITIONS)),
                "id", "title", "text")),
            "battle", Map.of("type", "object",
                "required", List.of("id", "provinceAddress", "attackers", "defenders", "outcome", "rationale", "evidence"),
                "description", "单场裁决。可另给 startedAt、controllerAddress 和 losses。每项损耗含 unitAddress、实际 formationAddress、personnel 或 equipment（itemId 或 name，及 count）")));
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
  private static Double optionalNumber(Map<String, Object> args, String key) {
    if (!args.containsKey(key)) return null;
    if (!(args.get(key) instanceof Number value)) throw new IllegalArgumentException(key + " 必须是数值");
    return value.doubleValue();
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
  private static List<String> route(Map<String, Object> args) {
    return args.containsKey("route") ? addresses(args, "route") : null;
  }
  private static List<String> addresses(Map<String, Object> args, String key) {
    if (!(args.get(key) instanceof List<?> list)) throw new IllegalArgumentException(key + " 须为地址数组");
    List<String> addresses = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text)) throw new IllegalArgumentException(key + " 各项须为地址");
      addresses.add(text);
    }
    return addresses;
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
