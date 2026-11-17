# 第一阶段收尾审计

> **状态**：有效（2026-11-17）
> **日期**：2026-11-17
> **本文性质**：实现状态/验收审计；不是设计裁定。
> **依据**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md) §8、[`../plans/2026-11-17-headless-core.md`](../plans/2026-11-17-headless-core.md)、[`../../agents/STATUS.md`](../../agents/STATUS.md)

## 结论

设计 §8.1 的 **第一阶段必须项**已完成；§8.3 的 10 条验收判据均有自动测试/CLI/文档证据。

上次审计列出的 4 项代码问题已修复：

1. State→StrategicRegion 一致性校验；
2. SupplyHub 的 capacity/range 字段；
3. 显式 EntityLink；
4. expectedContentHash 保存/打开守卫。

全量地图导入是否进 `mvn test`、地图源范围/许可属于**使用边界**，与代码缺陷分开记录。

## §8.3 验收判据

| # | 判据 | 状态 | 证据 |
|---:|---|---|---|
| 1 | NodeAddress 严格解析/拒绝非法路径 | PASS | `NodeAddressTest` |
| 2 | polygon 自交/重叠/显示点在外校验失败 | PASS | `WorldMapValidatorTest`、`RasterProvincePolygonizerTest` |
| 3 | land province 恰好一个 state；province 恰好一个 region | PASS | `WorldMapValidator` + Magna 导入 |
| 4 | 同 working copy 多次 apply 合并一个 ChangeSet | PASS | `TimelineServiceTest` |
| 5 | discard 后 hash 回快照 | PASS | `TimelineServiceTest` |
| 6 | 未 save 的 working copy 不能推进/开分支 | PASS | API 只接受 saved `NodeRef`；`TimelineServiceTest` |
| 7 | 只有 saved node 可作 parent；历史只能 fork | PASS | `TimelineServiceTest`、`SqliteTimelineStoreTest` |
| 8 | checkpoint+delta replay hash 一致 | PASS | `TimelineServiceTest`、CLI `verify` |
| 9 | fork 不受父分支后续影响 | PASS | `TimelineServiceTest` |
| 10 | `mvn test` / docs check 全绿 | PASS | 72 tests；`DOC CHECK: PASS` |

## 代码问题 1–4：已修复

### 1. State→StrategicRegion 一致性

- `WorldMapValidator` 新增 `STATE_SPANS_REGIONS`：一个 State 的省若跨多个 StrategicRegion 直接报错。
- 新增 `WorldMapValidatorTest.detectsStateSpanningStrategicRegions`。
- Magna 当前数据：2986 states / 253 regions，跨区 State = 0。

### 2. SupplyHub capacity/range

- `SupplyHub` 新字段：`Double capacity`、`Double range`，允许 `null` 表示源数据未知。
- 非 null 时必须有限且 >= 0；不把“未知”写成 0。
- 三参数构造器保留兼容；Magna 导入为未知 `null`。
- 新增 `WorldMapValidatorTest.supplyHubCapacityAndRangeMayBeUnknownButNotNegative`。

### 3. 显式 EntityLink

- 新模型：`EntityLink(key, category, targetId, displayPoint?, tags)`。
- 进入 `WorldMap`、`WorldChangeSet`、validator、canonical JSON、CLI detail 和 resolver。
- validator 检查已知 category 的 target 存在（province/state/region/supplyHub/railway）。
- 删除 province/state/region/hub/railway 时清理指向它的链接。
- 地址：`map/link/<key>`，例如 `main-0/map/link/province:1`。
- Magna 导入生成 **27504** 条 EntityLink。
- 程序不写死地图名/欧洲：worldId 从 `--source` 目录名派生，测试 fixture 使用 `community-map`。

### 4. expectedContentHash 并发守卫

- `TimelineService` 新增：
  - `openNext(savedRef, expectedContentHash)`
  - `openFork(savedRef, branch, expectedContentHash)`
  - `save(workingCopy, tick, savedBy, expectedContentHash)`
- 不匹配：`TIMELINE_EXPECTED_HASH_MISMATCH`，零写入，working copy 保持打开。
- 空白：`TIMELINE_EXPECTED_HASH_INVALID`。
- 新增 `TimelineServiceTest.expectedContentHashGuardsOpenForkAndSave`。
- 原有 head 检查 + SQLite 原子保存仍然保留。

## 使用边界（不是代码缺陷）

### 5. 全量导入不纳入 `mvn test`

- 自动测试使用 tiny BMP fixture；
- Magna 全量导入是手工/CLI smoke；
- 原因是用例需要约 1.66 GB 峰值内存和约 28 秒，不适合每次单元测试。
- 这是 CI/测试使用策略，不是代码错误。

### 6. 地图源范围与许可

- 当前源 Magna Europa 是**欧洲地图**，不是全球地图；
- 上游没有标准 LICENSE；
- raw/派生 DB 只放 gitignored `runtime/`，公开分发前需解决许可；
- 如需全球地图，需另选源或使用正版 HOI4 本机数据。
- 这是数据源使用决策，不是程序硬编码。

## 阶段外未实现

- ORBAT 原生存储 / 模板 / 运行时 UnitInstance；
- INFO 包/类型注册/长短文本/任意地址；
- DM/决策人权限；
- WorldSimulator 自己的 MCP / AgentLib tool surface；
- WebUI/只读 viewer；
- 战斗模型。

## MCP 能力现状

- **宿主 MCP：可用**。当前会话可以调用挂载的 `orbat_*`、`bma3_inbox` 等 MCP 工具。
- **WorldSimulator MCP：未实现**。仓库没有 AgentLib/MCP 依赖、MCP server 入口、工具 schema、权限/审批层；CLI 不是 MCP。
- 因此现在只能通过 CLI/Java API 操作 WorldSimulator，不能用其自身 MCP 操作。
