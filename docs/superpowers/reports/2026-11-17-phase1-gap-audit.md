# 第一阶段收尾审计

> **状态**：有效（2026-11-17）
> **日期**：2026-11-17
> **本文性质**：实现状态/验收审计；不是设计裁定。
> **依据**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md) §8，[`../plans/2026-11-17-headless-core.md`](../plans/2026-11-17-headless-core.md)，[`../../agents/STATUS.md`](../../agents/STATUS.md)

## 结论

设计 §8.1 列出的 **第一阶段 5 项必须实现**已经完成；§8.3 的 10 条验收判据有自动测试/CLI/文档证据覆盖。

但“第一阶段范围外但设计里已提到”的若干事项仍未实现：MCP、ORBAT、INFO、通用 resolver、WebUI/渲染器、DM 权限。

## §8.3 验收判据

| # | 判据 | 状态 | 证据 |
|---:|---|---|---|
| 1 | NodeAddress 严格解析/拒绝非法路径 | PASS | `NodeAddressTest` 9 tests |
| 2 | polygon 自交/重叠/显示点在外校验失败 | PASS | `WorldMapValidatorTest` / `RasterProvincePolygonizerTest` |
| 3 | land province 恰好一个 state；province 恰好一个 region | PASS（实际 map + validator） | Magna 导入 `states=2986`、`regions=253`、`unassigned=0` |
| 4 | 同 working copy 多次 apply 合并一个 ChangeSet | PASS | `TimelineServiceTest.sameWorkingCopyWithTwoWritesProducesOneOrderedChangeSet` |
| 5 | discard 后 hash 回快照 | PASS | `TimelineServiceTest.discardDropsMemoryChangesAndDoesNotMoveHead` |
| 6 | 未 save 的 working copy 不能推进/开分支 | PASS（API 结构约束） | `TimelineService` 只接收 `NodeRef` 作为 parent；未保存 copy 不是 node |
| 7 | 只有 saved node 可作 parent；历史只能 fork | PASS | `TimelineServiceTest`、`SqliteTimelineStoreTest` |
| 8 | checkpoint+delta replay hash 一致 | PASS | `TimelineServiceTest.periodicCheckpoint...`、CLI `verify` |
| 9 | fork 不受父分支后续影响 | PASS | `TimelineServiceTest.forkBranchIsIndependent...` |
| 10 | `mvn test` / docs check 全绿 | PASS | 68 tests；`DOC CHECK: PASS` |

## 第一阶段范围外的未完成项

| 事项 | 状态 | 说明 |
|---|---|---|
| WorldSimulator MCP / AgentLib tool surface | 未实现 | `pom.xml` 无 AgentLib/MCP 依赖；无 `WorldTool`、ToolRegistry、MCP server。当前 CLI 不是 MCP |
| ORBAT 原生存储 / 模板 / 运行时 UnitInstance | 未实现 | 仅在设计/侦察；单单位单节点最终态尚未落代码 |
| INFO 包/类型注册/长短文本/任意地址 | 未实现 | 仅在设计 |
| 通用实体 resolver | 部分 | 目前只有 `map/province|state|region|supplyHub|railway`；ORBAT/INFO/动态实体未接 |
| DM/决策人权限 | 未实现 | 当前 GM-only 设计，但尚无权限代码 |
| WebUI / 只读 viewer | 未实现 | 渲染方案未选型 |
| 战斗模型 | 未实现 | 设计上保持可插拔，V1 未落代码 |
| Docs 适配 | 未实现 | 当前只做结构化角色/文档地址预留 |

## 静态地图层的残余工程债

1. **State→StrategicRegion 归属一致性尚未显式校验**。现在 validator 校验每个 province 的 region 和反向列表，但没有直接检查“一个 State 的所有 province 必须在同一 region”。实际 Magna 导出的 2986 states 当前为 0 个跨区，但规则本身还应加 validator。
2. **SupplyHub 只有 `id/provinceId/level`**，尚未建模 `capacity/range`；源 `supply_nodes.txt` 只有节点声明，容量/范围需另找数据或明确派生规则。
3. **`EntityLink` 未作为独立实体表实现**；目前依靠 `Map` key 和 `MapAddressResolver` 定位。
4. **并发保存没有显式 `expectedContentHash` 参数**；当前用“base 必须仍是 head”+ SQLite 原子约束保证不覆盖，符合首版需求，但不是完整 CAS API。
5. **全量地图导入未纳入 `mvn test`**：测试使用 tiny BMP fixture；Magna 全量导入约 27.57 s、峰值约 1.55 GB，是手工 CLI smoke。
6. **源数据和许可**：Magna Europa 是欧洲图，raw/派生 DB 在 gitignored `runtime/`；上游无标准 LICENSE，公开分发前需确认。
7. **几何修复**：8 个省形由 `GeometryFixer` 修复；3 个省由 heightmap 推断 sea，仍需视觉/引擎复核。

## MCP 能力现状

- **当前会话挂载的 MCP 工具**：可以调用宿主提供的 `orbat_*`（workspace/query/plan/edit/commit/export 等）、`bma3_inbox` 等 MCP 工具。
- **WorldSimulator 自己的 MCP**：尚不可用。仓库还没有 AgentLib 依赖、MCP server 入口、工具 schema/权限/审批层，也没有把 CLI 包成 MCP tool。
- 结论：能使用宿主 MCP，不能通过 `WorldSimulator` 自身 MCP 操作世界。
