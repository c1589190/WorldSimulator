# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 11:10 UTC
> **当前目标**：记录用户裁决/设计；实现“静态 polygon 状态 + 时间线储存”最小核心。
> **当前阶段**：设计已记录，Java 骨架 WIP；渲染方案待选型。

## 源码基线

- 本仓 `~/WorldSimulator` 是单模块 Maven 工程，JDK 21。
- 已落盘：`pom.xml`、地址、polygon 地图、静态状态、ChangeSet、TimelineStore 接口。
- 尚未落盘：SQLite `TimelineStore` 实现、working copy/save/discard/ fork 服务、测试、CLI。
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

1. 实现 SQLite `TimelineStore` + `TimelineService` 的 genesis / working copy / save / discard / fork / replay。
2. 写 JUnit：严格地址、polygon 校验、ChangeSet 顺序、discard/save、只有 saved 可 fork、checkpoint replay hash。
3. 提供最小 CLI：初始化小世界、apply 两个变更、discard 一次、save 一次、fork 一次、回放校验。
4. 渲染方案先不落代码；等用户看完比较后裁定 Viewer 路线。
