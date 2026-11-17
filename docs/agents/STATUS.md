# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 11:37 UTC（19:37 CST）
> **当前目标**：headless core 硬化 + 静态地图实体 resolver。
> **当前阶段**：核心硬化与 map resolver 完成，等待用户选择 ORBAT/INFO 层；渲染方案仍待选型。
> **当前责任区**：`docs/superpowers/plans/2026-11-17-headless-core-hardening.md`（已完成）及 map resolver 实现补充。

## 源码基线

- 仓库：`~/WorldSimulator`，单模块 Maven 工程，JDK 21。
- Git：`main`，跟踪 `origin/main`；远程 `git@github.com:c1589190/WorldSimulator.git`。
- 已推送硬化基线：`1e2e429 docs: record headless core hardening evidence`。
- 当前未提交改动：静态地图 `MapAddressResolver`、`resolve` CLI 与对应测试；提交后以 `git log/status` 为准。
- 运行环境：WSL；人类客户端定位为只读查看器，不做复杂编辑器。
- 邻仓参考基线：
  - `~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；
  - `~/ProjectMosire` 当前工作树；AgentLib 契约以该仓文档为准；
  - DSH `orbat_*` 工作区当前 `sequence=15`，5 份编制文档（上一轮只读侦察）。

## 当前有效文档

- 用户设计原稿（含第二轮/第三轮逐字裁定）：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`
- 专题设计：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`
- Headless Core 实现计划：`docs/superpowers/plans/2026-11-17-headless-core.md`
- Headless Core 硬化计划：`docs/superpowers/plans/2026-11-17-headless-core-hardening.md`
- 硬化证据：`docs/superpowers/reports/2026-11-17-headless-core-hardening-report.md`
- 渲染方案比较：`docs/superpowers/reports/2026-11-17-renderer-options.md`
- 邻仓/DSH ORBAT 侦察：`docs/superpowers/reports/2026-11-17-neighbor-projects-recon.md`
- 设计裁定索引：`docs/superpowers/DESIGN_RULINGS.md`
- 文档规范：`docs/AGENTS.md`、`docs/superpowers/AGENTS.md`
- 快速构建/CLI：根 `README.md`

## 最近验证

| 项目 | 结果 | 证据 |
|---|---|---|
| 文档结构检查 | `PASS`（`python3 tools/check-docs.py`，rc=0） | 11 required / 2 specs / 3 reports |
| 单测 | `PASS`（`mvn clean test`，rc=0） | 60 tests / 0 failure / 0 error |
| 可运行打包 | `PASS`（`mvn -DskipTests package`，rc=0） | `target/worldsim-0.1.0-SNAPSHOT.jar`（约 16 MB） |
| 300 省 benchmark | `PASS`（rc=0） | 20×15；565 邻接；validationIssues=0；generate 130 ms / validate 36 ms / genesisSave 139 ms / deltaSave 117 ms / replay 43 ms |
| 500 省 benchmark | `PASS`（rc=0） | 25×20；955 邻接；validationIssues=0；generate 152 ms / validate 43 ms / genesisSave 178 ms / deltaSave 109 ms / replay 33 ms |
| 多进程写竞争 | `PASS`（1 test） | 两个真实 JVM 同时写 `main-1`，恰好一个成功，一个失败且无覆盖 |
| 地图地址 resolver | `PASS`（4 tests + CLI） | `main-2/map/province/east` → `province:east` / `East revised` |
| CLI verify | `PASS`（rc=0） | `/tmp/worldsim-bench500/bench.db` → `verifiedNodes=2` |
| 渲染方案 | 未选型 | `docs/superpowers/reports/2026-11-17-renderer-options.md` |

## 已实现

### Headless core 基础

- 严格 `NodeAddress`：`treeXXX-tickXXX[/<type>[/<id>...]]`，空白/空段/`.`/`..`/非法 tick/溢出均拒绝。
- 原生 polygon 省形 + holes + multi-part；JTS 校验合法性、面积、重叠、显示点、边界、归属与邻接。
- 有序 `WorldChangeSet`；同一 working copy 多次写入合并成一个增量，保留顺序，后写覆盖同字段。
- SQLite `timeline_nodes` + `checkpoints`；节点不可覆盖，父节点必须已保存，同分支父节点必须是 head。
- `TimelineService`：genesis、openNext、openFork、apply、discard、save、load/replay、verifyAll、显式 checkpoint。
- 回放：最近祖先 checkpoint + delta，逐节点 content hash 自检，最终状态再跑地图校验。
- fork-only：历史节点 `openNext` 报 `TIMELINE_FORK_REQUIRED`；新分支首节点可任意晚于父 tick。
- checkpoint 策略：创世、分支首节点、每 N 个节点（默认 N=16，CLI demo N=2）；checkpoint 是派生缓存。

### 本轮硬化

- SQLite schema `user_version=1`；更高版本拒绝；v0 空库/旧开发库最小迁移；初始化失败关闭连接。
- 合成 polygon 世界：20×15=300 省、25×20=500 省，州按行、战略区域按行块，自动推导邻接。
- `benchmark` CLI：记录生成、JTS 校验、genesis 保存、delta 保存、checkpoint+delta 回放耗时。
- 两个真实 JVM 的 `main-1` 写竞争测试：主键/head 约束下无覆盖。

### 静态地图 resolver

- `MapAddressResolver` 支持 `<node>/map/province|state|region|supplyHub/<id>`。
- 返回稳定 id（`province:east` 等）、kind、显示名和原始实体；显示名不作为身份。
- 未知 type / 缺 id / 多余路径段 / 实体不存在分别返回稳定 `AddressException` code。
- CLI `resolve --db <path> --address <node-address>` 可查询某节点上的地图实体。
- node 存在性由 `TimelineService.load` 负责；resolver 不解释节点语义。
- 60 个 JUnit 测试。

## 已记录的关键裁定

- 原生 polygon 是权威几何；渲染可不画 polygon，只显示 `displayPoint`/选中轮廓。
- 地址沿用 Simos 严格 `treeXXX-tickXXX[/<type>[/<id>...]]`。
- 同一节点按顺序合并多次写入；working copy 可 discard/save；只有 saved 节点能推进或 fork。
- 先只能 fork；不做历史节点原地改写/rebase。
- 单单位单节点只能有一个 effective final state。
- INFO 支持任意地址、短/长文本、自定义 typed payload、多包扩展。
- 当前只上 GM；DM/决策人后续再做。
- 不支持 HOI4 导出。
- 人类客户端主要是查看器；环境 WSL；渲染选型优先跨平台。

## 未验证 / 风险 / blocker

- benchmark 使用规则矩形，不代表真实不规则 polygon 的 JTS 成本；时间是单机单次观测，不是 SLA。
- 多进程测试只覆盖同节点竞争；未覆盖长事务、写队列、崩溃恢复、WAL checkpoint 压力和吞吐。
- `verifyAll` 仍逐节点回放；节点多时成本近 O(n²)，后续需按分支批量校验。
- schema 只有 v0→v1 初始化路径，尚无正式迁移框架。
- map resolver 只支持 province/state/region/supplyHub；adjacency/railway/unit/ORBAT/INFO 地址仍未接。
- ORBAT/INFO 运行时、DM 权限、MCP 工具面、WebUI 未实现。
- ORBAT 模板/初始编成/运行时实例边界、INFO 多包协议仍需用户裁定/细化。
- 渲染方案未定；WSL 到 Windows 浏览器 localhost 端口转发需实测。
- `~/SimulatorMosire` 没有根 LICENSE；只能参考行为，不能默认复制代码。

## 下一步

1. 与用户确认 ORBAT 模板/初始编成/运行时实例边界和 INFO 多包协议。
2. 设计并实现 ORBAT/INFO 的 ChangeSet 扩展与地址 resolver；仍保持一个节点一个单位最终态。
3. 渲染方案仍需用户选择；选 WSL 后端 + Windows 浏览器 WebGL2 后才开始只读 viewer。
