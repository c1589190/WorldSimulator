# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 11:28 UTC（19:28 CST）
> **当前目标**：完成 headless core：静态 polygon 状态 + SQLite 时间线 + working copy/save/discard/fork/replay + CLI/测试。
> **当前阶段**：实现与本地验证完成，等待用户评审；渲染方案仍待选型。
> **当前责任区**：`docs/superpowers/plans/2026-11-17-headless-core.md`（已完成）。

## 源码基线

- 仓库：`~/WorldSimulator`，单模块 Maven 工程，JDK 21。
- Git：`main`；远程 `origin=git@github.com:c1589190/WorldSimulator.git`。
- 远程已有初始提交：`e5c9cea Initial commit`（只含 `LICENSE`）。
- 本地实现提交：`af1bd92 feat: implement headless polygon timeline core`；随后收尾提交包含 README、STATUS、CLI 测试与两处回放校验强化。
- 工作树：收尾提交前有 README/STATUS/CLI 测试和回放校验改动；提交后以 `git status` 为准。
- 运行环境：WSL；人类客户端定位为只读查看器，不做复杂编辑器。
- 邻仓参考基线：
  - `~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；
  - `~/ProjectMosire` 当前工作树；AgentLib 契约以该仓文档为准；
  - DSH `orbat_*` 工作区当前 `sequence=15`，5 份编制文档（上一轮只读侦察）。

## 当前有效文档

- 用户设计原稿（含第二轮/第三轮逐字裁定）：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`
- 专题设计：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`
- 实现计划/完成边界：`docs/superpowers/plans/2026-11-17-headless-core.md`
- 渲染方案比较：`docs/superpowers/reports/2026-11-17-renderer-options.md`
- 邻仓/DSH ORBAT 侦察：`docs/superpowers/reports/2026-11-17-neighbor-projects-recon.md`
- 设计裁定索引：`docs/superpowers/DESIGN_RULINGS.md`
- 文档规范：`docs/AGENTS.md`、`docs/superpowers/AGENTS.md`
- 快速构建/CLI：根 `README.md`

## 最近验证

| 项目 | 结果 | 证据 |
|---|---|---|
| 文档结构检查 | `PASS`（`python3 tools/check-docs.py`，rc=0） | 11 required / 2 specs / 2 reports |
| 单测 | `PASS`（`mvn clean test`，rc=0） | 47 tests / 0 failure / 0 error |
| 可运行打包 | `PASS`（`mvn -DskipTests package`，rc=0） | `target/worldsim-0.1.0-SNAPSHOT.jar`（约 16 MB） |
| CLI demo | `PASS`（rc=0） | 4 节点；2 写入合并；discard 后 hash 复原；历史节点 fork 成功；`verifiedNodes=4` |
| CLI verify/replay/tree | `PASS`（均 rc=0） | `/tmp/worldsim-cli-final/demo.db` |
| JTS 真实规模压测 | `NOT_RUN` | 当前只有 2 省示例与单元级拓扑测试 |
| 多进程 SQLite 并发 | `NOT_RUN` | 当前为单连接 + 方法级同步 |
| 渲染方案 | 未选型 | `docs/superpowers/reports/2026-11-17-renderer-options.md` |

## 已实现的 headless 闭环

- 严格 `NodeAddress`：`treeXXX-tickXXX[/<type>[/<id>...]]`，空白/空段/`.`/`..`/非法 tick/溢出均拒绝。
- 原生 polygon 省形 + holes + multi-part；JTS 校验合法性、面积、重叠、显示点、边界、归属与邻接。
- 有序 `WorldChangeSet`；同一 working copy 多次写入合并成一个增量，保留顺序，后写覆盖同字段。
- SQLite `timeline_nodes` + `checkpoints`；节点不可覆盖，父节点必须已保存，同分支父节点必须是 head。
- `TimelineService`：genesis、openNext、openFork、apply、discard、save、load/replay、verifyAll、显式 checkpoint。
- 回放：最近祖先 checkpoint + delta，逐节点 content hash 自检，最终状态再跑地图校验。
- fork-only：历史节点 `openNext` 报 `TIMELINE_FORK_REQUIRED`；新分支首节点可任意晚于父 tick。
- checkpoint 策略：创世、分支首节点、每 N 个节点（默认 N=16，CLI demo N=2）；checkpoint 是派生缓存。
- 最小 CLI：`demo` / `tree` / `replay` / `verify`；JUnit 覆盖地址、polygon、ChangeSet、store、service、CLI。

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

- JTS 拓扑校验尚未在 200–500 省和 1 万+ polygon 真实规模上压测；邻接推导可能成为保存/校验热点。
- SQLite 当前是单连接 + 同步方法，适合单进程 POC；未验证多进程并发、WAL 争用和崩溃恢复窗口。
- `verifyAll` 逐节点重放，节点多时是近似全量重复读；后续需要按分支增量校验或批量报告。
- SQLite schema 目前没有 `user_version`/迁移框架；后续扩展 ORBAT/INFO ChangeSet 前需要补。
- 地址/实体分辨率只实现语法与地图 ID；通用 resolver、ORBAT/INFO 地址尚未实现。
- 未实现 ORBAT/INFO 运行时、DM 权限、MCP 工具面、WebUI。
- `~/SimulatorMosire` 没有根 LICENSE；只能参考行为，不能默认复制代码。
- DSH `mil-org` 兼容导出非无损；原生 ORBAT 尚未实现。
- 渲染方案未定；WSL 到 Windows 浏览器的 localhost 端口转发需实测。

## 下一步

1. 用户评审 headless core 的时间线语义、fork tick 规则和 SQLite API；有异议先改设计与测试。
2. 若核心通过，补 200–500 省 polygon 压测与多进程 SQLite 并发测试。
3. 再按专题设计扩展 ORBAT/INFO ChangeSet、实体 resolver 与 MCP 工具面。
4. 渲染方案暂不落代码；等用户选择 WSL 后端 + Windows 浏览器 WebGL2 路线后再做只读 viewer。
