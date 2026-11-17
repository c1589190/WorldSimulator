# Headless Core 实现计划

> **状态**：已完成（2026-11-17；验收与残余工程债见 `../reports/2026-11-17-phase1-gap-audit.md`）
> **日期**：2026-11-17
> **本文性质**：实现计划（派单责任区）；不是设计正本。
> **用户原话（逐字，2026-11-17）**：
>
> 「那你先实现一下headless core吧，哦对了你得先初始化git，git@github.com:c1589190/WorldSimulator.git是远程地址」
>
> **设计依据**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md) §5、§8。

## 责任区

只实现静态 polygon 世界 + 时间线持久化的 headless 最小闭环：

- 严格 `NodeAddress`；
- `WorldChangeSet` 顺序合并与规范 JSON；
- SQLite `TimelineStore`：nodes + checkpoints；
- `TimelineService`：genesis、open、apply、discard、save、fork、replay/hash 自检；
- JUnit 覆盖验收判据；
- 最小 CLI 演示与回放；
- 不接 ORBAT/INFO/MCP/WebUI。

## 时间语义

- 创世节点：`main-0`，全量 checkpoint，空 ChangeSet。
- 同分支推进：工作副本只能从该分支 head 的已保存节点打开；目标 tick 必须更大。
- fork：从任意已保存节点打开新分支；目标分支必须尚不存在；首节点 tick 必须大于父节点 tick。
- 历史节点不能原地推进；只能 fork。
- 工作副本不是节点；same working copy 的多次写入按调用顺序形成一个 ChangeSet。
- 保存时校验完整静态状态与内容 hash；回放时逐节点校验 hash。
- 并发通过“head 必须仍等于打开时节点”和 store 的原子约束拒绝，不产生半写节点。

## 验收

1. `mvn test` 全绿；
2. CLI `demo` 能在临时 SQLite 中完成 genesis/apply/discard/save/advance/fork/replay；
3. `python3 tools/check-docs.py` 全绿；
4. `STATUS.md` 写明实际命令、rc、测试数与剩余风险。

## 完成结果

- SQLite store 与 TimelineService 已落盘；
- §8.3 验收判据 10 条均有测试/CLI/文档证据；
- 当前全套 JUnit 72 tests 全绿；
- CLI demo/replay/verify/tree/import-hoi4 已运行；
- Magna Europa 全量地图导入并回放通过；
- 文档检查 `PASS`。

## 未验证边界 / 后续

- 已补真实 2 万+ province 导入和多进程写竞争；全量导入不在 `mvn test`。
- 尚未实现：ORBAT/INFO ChangeSet、通用 resolver、MCP/AgentLib tool surface、DM 权限、WebUI/渲染器。
- State→StrategicRegion 一致性、SupplyHub capacity/range、显式 expectedContentHash CAS 是残余工程债；详见阶段审计。
