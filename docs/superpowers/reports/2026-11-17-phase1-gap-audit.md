# 第一阶段收尾审计

> **状态**：有效（2026-11-17）
> **日期**：2026-11-17
> **本文性质**：实现状态/验收审计；不是设计裁定。
> **依据**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md) §8、[`../plans/2026-11-17-headless-core.md`](../plans/2026-11-17-headless-core.md)、[`../../agents/STATUS.md`](../../agents/STATUS.md)
> **追加复核**：2026-11-17 14:05 UTC，基线 `d9df31f`；新增两项已复现缺口见文末，原有 72 项测试通过不代表这些边界已覆盖。
> **局部取代（2026-11-17）**：本报告中“这两项缺口尚未修复”和“WorldSimulator MCP 未实现”为 14:05 UTC 审计快照；文件世界实施轮已补拓扑保存守卫、完整增量校验与 GM MCP，当前代码和验证见 `docs/agents/STATUS.md`。

## 结论

设计 §8.1 的 **第一阶段主路径**已实现；§8.3 的 10 条验收判据均有自动测试/CLI/文档证据。14:05 UTC 追加复核发现普通邻接与几何一致性、checkpoint 节点自身 delta 验证存在缺口；修复与新验证见本文局部取代头和当前 STATUS。

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

## 追加复核：需求与实现边界（2026-11-17 14:05 UTC）

用户本轮要求先理解需求、核对代码并说明后续工作。本轮只读核对与临时验证，未修改业务代码、未新增 JUnit、未重跑社区地图下载/全量导入。

### 本轮实际验证

- `mvn clean test`：rc=0；`target/surefire-reports/TEST-*.xml` 合计 72 tests / 0 failures / 0 errors / 0 skipped。
- `mvn -DskipTests package`：rc=0；日志 `/tmp/worldsim-review-package.log`。
- 临时 SQLite 上运行 CLI `demo`、`resolve --address main-2/map/province/east`、`verify`：均 rc=0；两次写入合并、discard 恢复、历史推进拒绝、fork 独立、4 节点验证、稳定身份 `province:east` 符合预期。输出 `/tmp/worldsim-review-demo-*.json`。
- `java -Xmx4g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify --db runtime/worlds/magna-europa.db`：rc=0，`verifiedNodes=1`；日志 `/tmp/worldsim-review-magna-verify.log`。仅核对现存创世节点，不是本轮全量导入或真实大图多节点编辑验证。
- SQLite 只读计数：21670 provinces / 2986 states / 253 regions / 674 hubs / 1921 railways / 27504 links / 65196 adjacencies；4096×3072。
- `python3 tools/check-docs.py`：rc=0；该脚本只检查结构/链接，不检查文档语义与代码一致性。

### 缺口 A：普通邻接可脱离 polygon 仍被保存

证据：`src/main/java/io/mosire/worldsim/map/WorldMapValidator.java:150` 的铁路检查信任已有 adjacency；`:184` 的邻接检查只检查引用/重复；`src/main/java/io/mosire/worldsim/state/WorldChangeSet.java:70` 更换省形不重算普通邻接；`src/main/java/io/mosire/worldsim/timeline/TimelineService.java:254` 保存沿用该 validator。

临时 Java 探针基于 `SampleWorld.create()`：

1. 保留 west 的 x=0..100，将 east 的左边界从 x=100 移到 x=120；两省之间形成间隔，显示点仍在省内。
2. 通过 `PutProvince` 更新 east，保留旧 land adjacency 与 `rail-main`。
3. 比较当前普通邻接和 `PolygonTopology.deriveAdjacencies`，再调用 validator、save、load。

实际输出（rc=0）：

```text
TOPOLOGY storedAdjacencies=1 derivedAdjacencies=0 validationIssues=0
TOPOLOGY save=main-1 replayAccepted=true
```

影响：Agent 编辑 polygon 后，旧普通邻接与铁路可在几何上断开而仍被接受。应先设计形状编辑时普通邻接的同步/拒绝规则，保留独立特殊邻接语义，再增加针对性回归。当前仅有地图实体读写原语，还不能宣称地图编辑已完整保障拓扑一致。

### 缺口 B：verifyAll 未验证 checkpoint 节点自身的 delta

证据：`src/main/java/io/mosire/worldsim/timeline/TimelineService.java:85` 选择最近 checkpoint；`:108` 从 checkpoint 的下一节点开始应用 delta；`:135` 的 `verifyAll` 对每个节点重复调用同一 `load` 路径。

临时 Java/SQLite 探针：

1. 临时库创建 genesis，使用 `checkpointEvery=2`，修改 east 名称并保存 `main-1`，使其带 checkpoint。
2. 仅在临时库中将 `main-1.changeset_json` 改为合法的空 ChangeSet，保留其原 checkpoint/hash。
3. 从父状态应用该空 delta，hash 与节点不符；调用现有 `verifyAll`。

实际输出（rc=0）：

```text
CHECKPOINT alteredDeltaMatchesSavedHash=false verifyAllAcceptedNodes=2
```

影响：正常查询可以利用 checkpoint 加速，但当前 `verifyAll` 的通过只说明所选 checkpoint/后续 delta 可加载，不能证明所有已存 delta 与父状态一致。应把完整校验与快速加载区分，在校验时覆盖 checkpoint 节点自己的 delta，并验证其结果和 checkpoint/hash 一致。

上述探针在临时文件/临时库中执行并清理，未触碰用户世界；原始输出 `/tmp/worldsim-review-probes.log`。这些是本轮新发现，未纳入现有 72 项 JUnit，状态为 **REPRODUCED / 未修复**。

### 文档冲突与后续契约

- 根索引旧“所有正文归 Docs / INFO 不承载长篇”与原稿 §6.1 第 8 条、D-011（均为 2026-11-17，后续用户裁定）冲突；已在根索引明确取代关系，INFO 长短文本/typed payload 属于既定需求。
- `../plans/2026-11-17-headless-core.md` 尾部把 State→Region、capacity/range、expected hash 列为残余债，是旧状态；已由本文“代码问题 1–4：已修复”取代。实际实现已存在，不应重复排期。
- 代码仍是单模块、只含 map 的 `StaticWorldState`（`src/main/java/io/mosire/worldsim/state/StaticWorldState.java:9`）；`WorldChange` 为 map 操作集合，未实现统一 Command 请求/幂等/preview/外部工作副本会话/原生包导出。`expectedContentHash` 在现有 Java API 中可选（`TimelineService.java:304`），外部写入口尚无必填约束。
- 建议顺序：补两项底座缺口 → Command 与原生导出契约 → 最小 GM MCP → ORBAT/INFO 统一状态闭环；只读预览用于地图质量复核，渲染选型单独处理。此为后续工程建议，不代表本轮已实现或用户已裁定其全部细节。
