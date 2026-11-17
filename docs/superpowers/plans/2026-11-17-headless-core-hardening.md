# Headless Core 硬化计划：规模、Schema 与并发

> **状态**：已完成（2026-11-17；证据见 `../reports/2026-11-17-headless-core-hardening-report.md`）
> **日期**：2026-11-17
> **本文性质**：实现计划（派单责任区）；不是设计正本。
> **用户原话（逐字，2026-11-17）**：
>
> 「额，然后继续？」
>
> **上游完成项**：[`2026-11-17-headless-core.md`](2026-11-17-headless-core.md)。  
> **设计依据**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md) §5、§8。

## 责任区

不改时间线业务语义，只补 headless core 的可扩展性和可信度：

1. SQLite schema `user_version` 与版本门禁；
2. 合成 300 省 polygon 世界生成器与 CLI benchmark；
3. 至少一次真实多进程 SQLite 写竞争测试；
4. 全量回归、文档与状态更新。

## 不做

- 不接 ORBAT/INFO/MCP/WebUI；
- 不改变 fork-only、saved-node-only、同 working copy 合并等裁定；
- 不引入渲染器；
- 不承诺多进程高吞吐，只验证不产生双写/半写。

## 验收

- 新库 `PRAGMA user_version=1`；更高版本库被拒绝；
- 300 省合成世界通过 polygon/引用校验，邻接数符合网格公式；
- `benchmark` CLI 能完成 300 省地图生成、genesis、delta、replay 并输出耗时；
- 两个真实 JVM 同时保存同一 `(branch,tick)`，恰好一个成功，另一个失败且无覆盖；
- `mvn clean test`、`mvn -DskipTests package`、文档检查全绿。

## 完成结果

- 300 省与 500 省 benchmark 均通过，详见报告；
- 多进程同节点竞争测试通过；
- `mvn clean test` 60 tests 全绿（含后续静态地图 resolver 测试）；
- `benchmark`、`verify` CLI 均返回 0；
- 文档检查 `PASS`。
