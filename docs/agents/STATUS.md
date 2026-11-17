# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 11:22 UTC
> **当前目标**：实现 headless core：静态 polygon 状态 + SQLite 时间线 + working copy/save/discard/fork/replay + CLI/测试。
> **当前阶段**：实现中（责任区：`docs/superpowers/plans/2026-11-17-headless-core.md`）；渲染方案待选型。

## 源码基线

- 本仓 `~/WorldSimulator` 是单模块 Maven 工程，JDK 21；已初始化 Git，`main`，远程 `origin=git@github.com:c1589190/WorldSimulator.git`。
- 初始提交：`167f7a1 chore: initialize WorldSimulator repository`。
- 已落盘：`pom.xml`、地址、polygon 地图、静态状态、ChangeSet、TimelineStore 接口。
- 实现中：SQLite `TimelineStore`、TimelineService/working copy、测试、CLI。
- 运行环境：WSL；人类客户端定位为只读查看器，不做复杂编辑器。
- 邻仓参考基线：
  - `~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；
  - `~/ProjectMosire` 当前工作树；AgentLib 契约以该仓文档为准；
  - DSH `orbat_*` 工作区当前 `sequence=15`，5 份编制文档（上一轮只读侦察）。

## 当前有效文档

- 用户设计原稿（含第二轮/第三轮逐字裁定）：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`
- 专题设计：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`
- 渲染方案比较：`docs/superpowers/reports/2026-11-17-renderer-options.md`
- 邻仓/DSH ORBAT 侦察：`docs/superpowers/reports/2026-11-17-neighbor-projects-recon.md`
- 设计裁定索引：`docs/superpowers/DESIGN_RULINGS.md`
- 文档规范：`docs/AGENTS.md`、`docs/superpowers/AGENTS.md`

## 最近验证

| 项目 | 结果 | 证据 |
|---|---|---|
| 文档结构检查 | `PASS`（`python3 tools/check-docs.py`） | 2 specs / 2 reports |
| Maven 编译 | `PASS`（`mvn -DskipTests compile`，rc=0） | 仅有骨架，无测试/运行 |
| 单测 | `NOT_RUN` | 尚无测试 |
| 时间线 SQLite 读写 | `NOT_RUN` | 只有 `TimelineStore` 接口 |
| 渲染方案 | 未选型 | `docs/superpowers/reports/2026-11-17-renderer-options.md` |

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

- 测试尚未写；`mvn test` 不能写 PASS。
- SQLite timeline storage / checkpoint replay / save-discard-fork 尚未实现。
- polygon 校验的 JTS 拓扑计算尚未在真实数据规模下压测。
- 渲染方案未定；WSL 到 Windows 浏览器的 localhost 端口转发需实测。
- `~/SimulatorMosire` 没有根 LICENSE；只能参考行为，不能默认复制代码。
- DSH `mil-org` 兼容导出非无损；原生 ORBAT 尚未实现。
- INFO 多包协议/长文本存储格式尚未设计细化。

## 下一步

1. 完成 SQLite `TimelineStore` + `TimelineService`。
2. 写 JUnit 并实际运行 `mvn test`。
3. 跑最小 CLI demo 与文档检查。
4. 更新本 STATUS 的实测结果；渲染方案暂不落代码。
