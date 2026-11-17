# WorldSimulator 文档入口

> **当前阶段**：Mappa Mundi 社区地图全量导入完成（2026-11-17；20753 省；65 tests；静态 polygon 时间线可回放）
> **当前状态入口**：[`agents/STATUS.md`](agents/STATUS.md)  
> **用户设计原稿**：[`superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`](superpowers/specs/2026-11-17-worldsim-architecture-design-source.md)  
> **文档系统规则**：[`AGENTS.md`](AGENTS.md)

## 先读哪里

| 需要 | 入口 |
|---|---|
| 当前状态、WIP、未验证项、下一步 | [`agents/STATUS.md`](agents/STATUS.md) |
| 根目录构建/CLI 快速入口 | [`../README.md`](../README.md) |
| 用户裁定逐字原话 | [`superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`](superpowers/specs/2026-11-17-worldsim-architecture-design-source.md) |
| 地图 + ORBAT + INFO + 时间线 + MCP 专题设计 | [`superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`](superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md) |
| Headless Core 实现计划/完成边界 | [`superpowers/plans/2026-11-17-headless-core.md`](superpowers/plans/2026-11-17-headless-core.md) |
| Headless Core 硬化计划 | [`superpowers/plans/2026-11-17-headless-core-hardening.md`](superpowers/plans/2026-11-17-headless-core-hardening.md) |
| 规模/schema/多进程证据 | [`superpowers/reports/2026-11-17-headless-core-hardening-report.md`](superpowers/reports/2026-11-17-headless-core-hardening-report.md) |
| 社区地图导入计划 | [`superpowers/plans/2026-11-17-community-map-import.md`](superpowers/plans/2026-11-17-community-map-import.md) |
| Mappa Mundi 全量导入证据 | [`superpowers/reports/2026-11-17-mappa-mundi-import-report.md`](superpowers/reports/2026-11-17-mappa-mundi-import-report.md) |
| 渲染方案比较 | [`superpowers/reports/2026-11-17-renderer-options.md`](superpowers/reports/2026-11-17-renderer-options.md) |
| 邻仓/DSH ORBAT 侦察证据 | [`superpowers/reports/2026-11-17-neighbor-projects-recon.md`](superpowers/reports/2026-11-17-neighbor-projects-recon.md) |
| 设计裁定索引 | [`superpowers/DESIGN_RULINGS.md`](superpowers/DESIGN_RULINGS.md) |
| 文档规范（设计/维护分账） | [`AGENTS.md`](AGENTS.md)、[`superpowers/AGENTS.md`](superpowers/AGENTS.md) |
| HOI4 社区调研（历史输入） | [`hoi4-community-map-resources-survey.md`](hoi4-community-map-resources-survey.md) |
| 旧 Agent 世界模型草案（历史） | [`agent-world-model-draft.md`](agent-world-model-draft.md) |

## 当前已裁定的关键点

- 原生 polygon 是省份权威几何；渲染可以不画 polygon，只显示 `displayPoint`/选中轮廓。
- 地址沿用 Simos 严格 `treeXXX-tickXXX[/<type>[/<id>...]]`。
- 同一节点内多次写入按顺序合并；working copy 可 `discard` 或 `save`；只有已保存节点能推进/开分支。
- **先只能 fork**，不做历史节点原地重写/rebase。
- 单单位、单节点只能有一个 effective final state。
- INFO 支持任意地址、短文本、长文本、自定义 typed payload 与多包扩展。
- 当前只上 GM；DM/决策人后续再做。
- **不支持 HOI4 导出**。
- 当前先做时间线储存 + 静态状态；人类客户端主要查看，不做复杂编辑器；运行环境 WSL。

## 当前未定项

- 渲染方案与 WSL 部署形态；
- ORBAT 模板/初始编成/运行时实例的正式边界；
- 州/战略区域/通用 Area 的重叠语义；
- 静态状态持久化格式；
- 地图逻辑范围/POC 规模；
- INFO 多包协议；
- GM 审计/回执强度；
- 首版战斗模型范围。

详见 [`superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`](superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md) 第 9 节。
