# 邻仓与 DSH ORBAT 侦察报告

> **状态**：调查证据（2026-11-17）  
> **本文性质**：只读侦察；事实给 `file:line` 或工具调用结果，不冒充设计裁定。  
> **关联设计**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md)  
> **验证边界**：本报告没有修改邻仓、没有编译/运行 WorldSimulator、没有导入 HOI4 游戏资产。

---

## 0. 侦察结论

1. **SimulatorMosire 复用为“文档规范 + 时间线/工具面参考”可行；原地改造成 province 地图不建议。**  
   `HexCoord` 在当前 SimulatorMosire 的 473 个 Java 源文件中出现，横跨 app/economy/unit/map/store 等 13 个模块；替换为 province ID/几何不是一个小改。

2. **Simos 的新时间线目标已经明确为 `treeXXX-tickXXX`，但现有代码仍有 `revisions` 表。**  
   新项目应直接采用 branch+tick 节点模型，而不是先照抄旧 revision 实现。

3. **ProjectMosire/AgentLib 的工具、MCP、权限、审批、决策包契约足以承载新工具面。**  
   建议新写一层薄 `WorldTool` SPI，复用 `AgentTool`/`ToolRegistry`/`AgentToMcpServer`/`ToolCallAuthorizer`/`ToolGate`/`DecisionPackages`，不要把 Simos 的领域工具类型拖进来。

4. **DSH ORBAT 是成熟的编制编辑器格式，但不是无损原生运行时格式。**  
   `mil-org` 导出会丢内部节点 id、历史/sequence/资源 revision；新系统必须保存自己的原生 ORBAT 文档和节点 id，把 `mil-org` 当兼容导出。

5. **EarthToHoI4 的栅格地图流水线可作为 HOI4-like 地图规范参考。**  
   它已经在代码里实现省/州/战略区域/补给节点/铁路/校验；但生成物许可混合，且当前不生成工厂/资源。

---

## 1. SimulatorMosire 文档系统

### 1.1 两条轴

`~/SimulatorMosire/docs/AGENTS.md` 明确区分：

- 项目设计：`docs/superpowers/specs/**`，用户设计原稿是最高权威；
- 项目维护：`docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`、`docs/agents/STATUS.md` 等。

关键规则（摘录）：

- 一条规则只能有一个主正本，另一侧放链接；
- 用户原话逐字保留，日期取实际日期；
- 旧文档不删，加状态/取代头；
- 没跑过的验证只写 `NOT_RUN` / 未验证 / `BLOCKED`；
- 现状事实给 `file:line`；跑过的结论给命令、rc、关键输出。

证据：`~/SimulatorMosire/docs/AGENTS.md:1-190`。  
本仓已按此结构建立 `AGENTS.md`、`docs/AGENTS.md`、`docs/superpowers/**`、`docs/agents/**`。

### 1.2 文档分层

`~/SimulatorMosire/docs/AGENTS.md` 的分层包含：

- 常驻路由 `AGENTS.md`；
- 目录局部 `AGENTS.md`；
- 实时状态 `docs/agents/STATUS.md`；
- 维护裁定 `docs/agents/MAINTENANCE_RULINGS.md`；
- 设计原稿/专题 `docs/superpowers/specs/**`；
- 计划/历史状态/报告/评审；
- Skill 与机械检查。

本仓采用同样骨架，但将“工程 Skill”暂缓，不批量造空 Skill。

---

## 2. SimulatorMosire 地图现状

### 2.1 地图是 hex 语义

`~/SimulatorMosire/simos-map/src/main/java/io/mosire/simos/map/GameMap.java:35-55` 的 `GameMap` 以：

- `Map<HexCoord, HexCell> hexes`
- `Map<BlockId, TerrainBlock> terrainBlocks`
- `Map<RegionId, Region> regions`
- `Map<CityId, City> cities`
- `Map<PathwayId, Pathway> pathways`
- `Map<EdgeRef, EdgeTags> edges`

为核心。`HexCell` 只存 `height` + `HexLand`，地形由 `TerrainBlocks` 权威；`Region` 是 `Set<HexCoord>` + boundary（`Region.java:47-72`）；`City.at` 是 `HexCoord`（`City.java:33-49`）。

### 2.2 HexCoord 影响面

实测命令：

```bash
cd ~/SimulatorMosire
for d in simos-*; do
  c=$(grep -RIl --include='*.java' 'HexCoord' $d/src/main/java 2>/dev/null | wc -l)
  [ "$c" -gt 0 ] && echo "$d $c"
done
grep -RIl --include='*.java' 'HexCoord' simos-*/src/main/java | wc -l
```

结果摘要：

| 模块 | 引用 HexCoord 的 Java 文件数 |
|---|---:|
| `simos-map` | 64 |
| `simos-app` | 89 |
| `simos-economy` | 165 |
| `simos-economy-api` | 33 |
| `simos-gov` | 32 |
| `simos-unit` | 28 |
| `simos-army` | 16 |
| `simos-social` | 29 |
| 其他 | ... |
| **总计** | **473** |

证据不是“所有文件都改”，而是说明 **HexCoord 已跨越模块边界**：`Unit.position`、`Army` 视野/部署、经济地块、政府辖区、app 工具视图等都可能引用它。  
在 SimulatorMosire 里原地替换会迫使多个领域模块改类型、改工具面、改测试，容易破坏既有经济/军队功能。

### 2.3 结论

新项目应**从第一天以 province 为原子单位**，不要先做 hex 再抽象。若需要复用 Simos 的算法，只复用与 hex 无关的部分（时间线、命令/ChangeSet、工具面、地址、文档规则）。

---

## 3. SimulatorMosire 时间线

### 3.1 用户目标

`~/SimulatorMosire/docs/superpowers/specs/2026-11-13-timeline-node-editor-and-address-query-design.md` 明确：

- 项目只有一个状态机：时间线；
- 节点增量存储：基线全量，后续相对父节点增量；
- 分支从已有节点分叉；
- 节点地址 `treeXXX-tickXXX`；
- 弃用 rev，同一 `(branch, tick)` 对应一个节点；
- 任意节点可进入地图编辑；
- 修改底图要有合法性检查机；
- 地址可表达 `branch + tick + 类型 + 子类型 + 稳定身份`；
- 所有实体地址可查询；
- 自然语言正文归文档系统。

### 3.2 现有实现事实

当前 `Timeline` 仍以 `revisions` 表为核心：

- `~/SimulatorMosire/simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java:79-92`：`revisions` 表列、`branch/revision/parent/.../changeset_json`；
- `Timeline.java:95-110`：D-036 同 tick 原地更新当前节点（`UPDATE ... WHERE branch=? AND revision=?`）；
- `Timeline.java:334-345`：`revisionAtTick(...)` 取同 tick 最大 revision；
- `Timeline.java:274-315`：`fork` 写新分支 `revision=1`，parent 指向源分支/源 revision。

这说明：**Simos 的“目标设计”已经是 branch/tick，但“现有代码”仍有 revision 兼容层和旧表结构。**  
新项目若要做真正的节点地址，建议直接：

```text
TimelineNode(branchId, tick, parentNode?, changeSet, contentHash, checkpointRef?)
```

不再让 `revision` 成为公共 API 的节点版本；`revision` 最多作为内部存储行号或审计字段，不参与地址。

### 3.3 检查点/增量

`Timeline.hasCheckpoint` 的规则（`Timeline.java:435-500`）：

- 每 N 个 revision 一个 checkpoint；
- 分叉点必须有一次 checkpoint；
- 创世节点有 checkpoint。

新项目可沿用“检查点按节点数/分叉点创建”的思想，但应直接按 `(branch, tick)` 判定，而不是 revision。

---

## 4. ProjectMosire / AgentLib

### 4.1 AgentTool 契约

`~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/tool/AgentTool.java` 定义：

- `name()` / `description()` / `jsonSchema()`；
- `spec()`：权限三要素；
- `roles()`：角色声明；
- `gate(ToolContext)`：审批闸位；
- `resources()`：资源命名空间；
- `ledgerArgs()`：事件脱敏；
- `execute(ToolContext)`。

关键语义：

- 工具自身不做工具名级权限判断；由 `ToolExecutionGuard` / `ToolCallAuthorizer` 负责；
- 资源级判定经 `ToolContext.resources()` 唯一入口；
- `roles()` 空集 = 本维不表态；非空 = 收窄；
- `resources()` 默认 `NONE`；使用资源必须显式声明；
- `gate()` 只负责“要不要人看”，不自行放行。

### 4.2 MCP server

`~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/mcp/AgentToMcpServer.java`：

- 将本地 `ToolRegistry` 暴露为 MCP stdio server；
- 启动时枚举现有工具；
- 监听 `ToolRegistry.onChange` 增量同步，发出 `notifications/tools/list_changed`；
- 调用仍走运行侧 `ToolExecutionGuard` / authorizer；
- 有 `ToolSpec.noExport()` 的工具不进入暴露面。

这正好适合“工具面动态变化、按 profile 加载”的需求。

### 4.3 权限/审批

`~/ProjectMosire/设计-审批与工具内权限.md`、`设计-MCP-HTTP安全.md`、`设计-上下文注入与动态工具面.md` 提供：

- 工具三分类：GM-only / DM-approval / DM-free；
- `ToolGate.Ask` 为敏感操作；
- `ToolCallAuthorizer` 是唯一入口；
- 动态 `ToolSurface` 每次请求可变；
- `ContextInjection` 在压缩后、LLM 前注入工具面变化；
- 系统提示词不应硬编码工具目录。

### 4.4 决策包

`~/ProjectMosire/docs/upstream/agentlib-decision-contract.md` 定义：

- `DecisionPackage` / `PackageItem` / `Decision` / `DecisionPackageSnapshot`；
- `contentVersion` 乐观并发；
- 逐条裁决 `DecisionItemVerdict`；
- `prepareCommit` / `completeCommit` 一个包 = 一条事务/revision；
- MCP 工具是决策包审核面；
- 包的状态机不读系统时钟，时间由宿主注入。

对 WorldSimulator 的启示：

- 批量地图/ORBAT 编辑应使用“预览/决策包/逐条裁决/提交”模式；
- 提交接口必须把“变更 + 审批结果 + 回执”放进同一事务；
- 不要把审批状态放进地图/ORBAT 领域数据。

### 4.5 Docs 契约

`~/ProjectMosire/docs/upstream/docs-storage-contract.md` 要求：

- Docs 的文件 IO 经 Workspace `resolveForIo`，拒绝软链逃逸；
- 按行读取有上限；
- 原子写；
- 存储可插拔；
- 领域模块不认识 Docs 的内部世界。

这与 Simos 的“自然语言记录只归文档系统”一致。

---

## 5. DSH ORBAT 工作台

### 5.1 当前工作区事实

调用 `orbat_workspace`：

- `sequence = 15`；
- 5 份文档：
  - 蓝方 · 青松步兵营（revision 4，19 nodes，140 personnel，108 equipment）；
  - 红方 · 赤岩步兵营（revision 3，19 nodes）；
  - 示例 · 晨曦步兵连（revision 1，13 nodes）；
  - 苏联近卫第4坦克师 · 1988年（revision 1，15 nodes，旧数据大量 `legacyAmbiguous`）；
  - 苏联近卫第4坦克师 · 1988年（新建）（revision 8，162 nodes，8490 personnel，2183 equipment）。

`orbat_resources`：装备目录/模板库当前为空（`revision=0`），不影响编制读写，但说明战斗能力解析不能依赖已有目录。

### 5.2 能力词表

`orbat_workspace.capabilities`：

- echelons：`army, corps, division, brigade, regiment, battalion, company, platoon, section, squad, team, individual`
- types：`infantry, light_infantry, mech, armor, light_armor, heavy_armor, airborne, air_assault, mountain, recon, recon_mech, wheeled_inf, artillery, mlrs, mortar, anti_tank, aaa, towed_w, towed_t, rotary, fixedwing, attack_heli, uav_unit, engineer, signal, medical, supply, ms_sfgpuci, ...`
- upstream：`Army_Editor 1.8.0`
- preview modes：`editor`, `list`

### 5.3 节点/统计语义

`orbat_read` 的节点包含：

- `id`、`name`、`type`、`echelon`；
- `personnel` / `personnelMax`；
- `equipment[]`（name/count）；
- `children[]`；
- `customName` / `layout` / `collapsed` / `stack` / `remark` / `mod`；
- `custom`（可含 `custom.orbat` 证据状态）；
- `hiddenChildren`（视图截断提示）。

`stats.coverage` 报告：

- `personnel.known/unknown/estimated/legacyAmbiguous`；
- `equipment.known/knownTotal/unknown/estimated/legacyAmbiguous`；
- `legacyAmbiguous` 的来源包括未标注 0 和空装备清单。

结论：

- `personnel`/`equipment` 是**本级直属**，不是含下属合计；
- 空/0 不能直接当“确认没有”；
- 证据状态必须保留；
- 统计覆盖要作为交付的一部分，不能只报总数。

### 5.4 `mil-org` 导出

调用 `orbat_export` 导出蓝方营：

```json
{
  "path": ".../armies-15-3407d33d.json",
  "format": "mil-org",
  "lossReport": [
    {"code":"history","message":"兼容文件不包含修订历史、sequence 或资源修订"},
    {"code":"internal-node-ids","count":19,"message":"兼容文件不包含内部节点 id；重新导入会生成新 id"}
  ]
}
```

`mil-org` 根字段：

- `kind`, `version`, `app`, `time`, `name`, `personnel`, `equipTotal`, `data`；
- `data` 是递归树：`name/type/echelon/personnel/personnelMax/equipment/children/...`。

关键结论：

- 兼容导出**不是无损**；
- 内部文档 id、节点 id、历史、证据/模板/别名、资源 revision 可能丢失；
- 新系统必须保存原生 ORBAT 文档，并为 `mil-org` 导出返回 `lossReport`；
- 不能宣称“文件已生成 = 原编辑器往返无损”。

### 5.5 ORBAT 与地图结合的方式

ORBAT 本身没有 province 位置。结合需要新增**部署/运行时实例**：

```text
OrbatNode (template/authorized)
        │ 1..n
        ▼
UnitInstance (runtime)
   unitId, orbatDocumentId, orbatNodeId,
   provinceId, parentUnitId, status,
   movement, currentPersonnel, currentEquipment,
   supply, decisionMakerId
```

不在 `mil-org` JSON 里硬塞 `provinceId` 的原因：

- 会破坏与 DSH/Army_Editor 的兼容；
- 部署随 tick 变化，而 ORBAT 模板是低频数据；
- 同一模板可以在不同位置生成多个实例。

---

## 6. EarthToHoI4 的地图规范证据

侦察副本：`/tmp/EarthToHoI4`（上游 `https://github.com/EdouardSaladier/EarthToHoI4`，MIT 代码；数据许可另计）。

### 6.1 州/省关系

`src/provinces.py:3-4` 明确：

- 省从州中切出；
- **每个省恰好属于一个州**。

`src/strategic.py:6-13`：

- 陆地区域由整个州构成；
- 每个省恰好属于一个战略区域；
- 无省的区域/无区域的省都会报错。

### 6.2 关键校验

`src/hoi4mod.py:3135-3285` 的 `check_mod` 实际检查：

- `definition.csv` CRLF；
- definition ID `0..N` 无缺口；
- land province 有 continent；
- 省不能同时属于两个州；
- land 不能属于 sea/unknown；
- state id `1..N`；
- 所有 land province 必须在 state 中；
- province 不能同时在两个 strategic region；
- 所有省必须在 strategic region 中；
- 海岸标志与 bitmap 一致（包含地图横向 wrap）；
- 每个 coastal land province 有 port building；
- 含海省的战略区域必须连通；
- railway 省链有效；
- supply node 必须在 state 中；
- 不能有悬空的 state/province/region id 引用。

### 6.3 补给/铁路规范

`src/hoi4mod.py:1981-2016`：

- 每个 owned state 的 main province 作为 supply node；
- 相邻州之间按主省中心距离建最小生成树；
- 每条铁路穿过两端州内的省路径；
- 输出 `railways.txt` / `supply_nodes.txt`。

`src/hoi4mod.py:2590-2618`：

- state 写 `manpower`、`state_category`、`provinces`、`history`、`local_supplies`；
- 当前生成器不生成工厂/资源（README 明确 “no factories or resources yet”）。

### 6.4 对 WorldSimulator 的启发

- HOI4-like 规范可以直接借鉴：省→州→战略区域的严格包含关系、补给节点/铁路、海岸/港口、ID 连续；
- 但不要把 EarthToHoI4 的生成数据当开放资产；
- 首版可只实现“省/州/区域/补给节点/铁路 + 校验”，不实现完整 HOI4 导出；
- 如果做导出，BMP/CSV 和港口/建筑/版本校验是单独的工作包，不应与内部地图模型耦合。

---

## 7. 风险清单

| 风险 | 证据 | 处置方向 |
|---|---|---|
| 原地改 Simos 为 province 成本过高 | 473 个 Java 文件引用 `HexCoord` | 新项目/新模块，不从 hex 迁移 |
| Simos 时间线代码与目标口径不一致 | `Timeline` 仍以 revision 表/`revisionAtTick` 为中心 | 新 Core 直接 branch+tick，不复制旧表 |
| ORBAT `mil-org` 非无损 | `orbat_export.lossReport` | 原生 ORBAT 持久化 + 兼容导出报告 |
| ORBAT 无地图位置/当前状态 | DSH 格式字段清单 | 增加 `UnitInstance`，不回写模板 |
| INFO 被误当世界真值 | Simos INFO 是感知层 | 地图/ORBAT 真值，INFO 只读投影 |
| 自然语言混入 INFO | 用户/Simos 文档边界 | 正文走 Docs，INFO 只存结构化 value + docRef |
| 社区地图许可不清 | Mappa Mundi/Blank Map 无标准 LICENSE | 只抄结构/算法；用开放数据/合成地图 |
| 直接 HOI4 导出约束多（历史调查） | EarthToHoI4 `check_mod` | 已裁定不做 HOI4 导出；保留为历史风险记录 |
| ORBAT 格式/实现许可未核 | `orbat_*` 能力来自 DSH/Army_Editor 1.8.0 兼容面 | 只做格式兼容、独立实现；复制代码/资源前先确认许可 |
| 工具面过宽 | Simos/AgentLib 动态工具面经验 | core + profiles；当前 GM 分面，DM 后续 |

---

## 8. 未验证项

- 未编译/运行 WorldSimulator（尚无代码）。
- 未在真实 MCP 客户端中调用新的 `WorldTool` 面。
- 未验证 `mil-org` 往返后 `custom.orbat` 的完整恢复（当前只看到导出损失报告；需要在原型中实测）。
- 未验证 DSH ORBAT 的 native export/import 在跨版本时的兼容性。
- 未验证栅格省份 + 大世界（5632×2048）在 Java 服务中的内存/延迟。
- 未验证从 checkpoint + delta 回放后节点内容哈希一致。
