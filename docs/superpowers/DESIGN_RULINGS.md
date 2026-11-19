# DESIGN_RULINGS.md —— 设计裁定索引

> **状态**：有效（2026-11-19 更新）
> **权威原稿**：[`specs/2026-11-17-worldsim-architecture-design-source.md`](specs/2026-11-17-worldsim-architecture-design-source.md)
> **说明**：本文件只做索引；正文在原稿/专题设计中。用户原话逐字保留在原稿，不在此改写。

| 编号 | 日期 | 来源 | 裁定摘要 | 主正本 | 状态 |
|---|---|---|---|---|---|
| D-001 | 2026-11-17 | 用户原话 | 放弃 SimulatorMosire 的细化六边形地图，转向 HOI4 风格的不规则省份地图 | `specs/2026-11-17-worldsim-architecture-design-source.md` §1 | 有效 |
| D-002 | 2026-11-17 | 用户原话 | 文档规范照搬/适配 SimulatorMosire 的设计/维护分账、状态头、原话与取代规则 | `docs/AGENTS.md`、`docs/superpowers/AGENTS.md` | 有效 |
| D-003 | 2026-11-17 | 用户原话 | 基于 ProjectMosire 的 AgentLib 封装 MCP 工具，让 Agent 编辑/导出世界 | 原稿 §2、专题设计 §6 | 有效（后续实现） |
| D-004 | 2026-11-17 | 用户原话 | 实现 Simos 风格的时间线管理 | 原稿 §2、专题设计 §5 | 有效 |
| D-005 | 2026-11-17 | 用户原话 | 不采用 HOI4 军事系统；用 INFO + DSH 编制编辑器格式（ORBAT）结合 HOI4 风格地图 | 原稿 §2、专题设计 §3/§4 | 有效 |
| D-006 | 2026-11-17 | 用户原话（第二轮） | **不支持 HOI4 导出**；不再设计/排期可加载 HOI4 mod | 原稿 §6.1 | 有效，取代旧专题的 HOI4 adapter 选项 |
| D-007 | 2026-11-17 | 用户原话（第二轮） | 地图原生支持 polygon；polygon 是权威几何；渲染可不画 polygon，只记录显示坐标/选中轮廓 | 原稿 §6.1、专题设计 §2 | 有效，取代旧“栅格为权威”的工程建议 |
| D-008 | 2026-11-17 | 用户原话（第二轮） | 多次写入按顺序合并到同一节点；不做 undo；working copy 可 discard 回快照或 save 成节点；只有 saved 节点能推进/开分支 | 原稿 §6.1、专题设计 §5 | 有效 |
| D-009 | 2026-11-17 | 用户原话（第二轮） | 历史修改**先只能 fork**，不做原地重写/rebase | 原稿 §6.1、专题设计 §5.4 | 有效 |
| D-010 | 2026-11-17 | 用户原话（第二轮） | 单个单位在单个节点只能有一个 effective final state；内部历史/变更记录自定 | 原稿 §6.1、专题设计 §3.3 | 有效 |
| D-011 | 2026-11-17 | 用户原话（第二轮） | INFO 支持任意地址、短文本、长文本、自定义 typed info、多包/扩展格式 | 原稿 §6.1、专题设计 §4 | 有效，取代旧“长文本只进 Docs”的建议 |
| D-012 | 2026-11-17 | 用户原话（第二轮） | 当前只上 GM；GM 原则上可做任何事；DM/决策人权限后续再做 | 原稿 §6.1、专题设计 §6.5 | 有效 |
| D-013 | 2026-11-17 | 用户原话（第三轮） | 运行环境 WSL；人类客户端主要是查看，不做复杂编辑器；渲染选型优先跨平台 | 原稿 §7、[只读地图查看器](specs/2026-11-19-readonly-map-viewer.md) | 有效 |
| D-014 | 2026-11-17 | 用户原话（第四轮） | 要直接导入/照搬可自行下载的 HOI4 社区地图，不能只做合成 fixture，也不能依赖用户本机 HOI4 安装 | 原稿 §8、`plans/2026-11-17-community-map-import.md` | 有效 |
| D-015 | 2026-11-17 | 用户原话（第五轮） | 社区地图已有的 strategic region/supply/railway 等数据应优先从源文件导入；不默认自动生成替代。缺失时先审计源文件、换源或报告 | 原稿 §9、`reports/2026-11-17-magna-europa-import-report.md` | 有效 |
| D-016 | 2026-11-17 | 用户选择（第六轮） | 当前社区地图源选用 Magna Europa Reforged（欧洲、HOI4 1.19、完整 region/supply/railway）；Mappa Mundi 降为历史对照 | 原稿 §10、`reports/2026-11-17-magna-europa-import-report.md` | 有效 |
| D-017 | 2026-11-17 | 用户原话（world 目录） | 正常启动检测并只从运行目录的 `world/` 加载世界；强调内容可查看性，按同一世界单实例评估存储 | 原稿 §11、专题设计 §11 | 有效；SQL 范围已由 D-018 裁定 |
| D-018 | 2026-11-17 | 用户选择（world 存储） | 选择 JSON/文本为权威世界存档，SQLite 仅可作可删除重建的索引；按提出的目录、存储、迁移和 MCP 顺序实现 | 原稿 §12、专题设计 §11 | 有效；本阶段已实现，当前范围见 STATUS |
| D-019 | 2026-11-19 | 用户原话 | 地名与地理索引、国家等实体查询和军队单位沿用同一严格节点地址体系 | [原稿 §13](specs/2026-11-17-worldsim-architecture-design-source.md) | 有效，补充原稿 §6.1 第 4 条 |
| D-020 | 2026-11-19 | 用户选择 | 从 `main-0` 分叉做 1936 葡萄牙样例 | [原稿 §14](specs/2026-11-17-worldsim-architecture-design-source.md) | 有效 |
| D-021 | 2026-11-19 | 用户原话 | 地图引用抽象单位地址，具体模块解释单位，理想与实际编制采用通用格式 | [原稿 §14](specs/2026-11-17-worldsim-architecture-design-source.md)、[实施契约](specs/2026-11-19-unit-address-and-orbat-format.md) | 有效 |
| D-022 | 2026-11-19 | 用户原话 | 地图使用大兵牌，缩小聚合、放大展开，选中时在左侧独立显示编制 | [原稿 §15](specs/2026-11-17-worldsim-architecture-design-source.md)、[查看器专题](specs/2026-11-19-readonly-map-viewer.md) | 有效 |
| D-023 | 2026-11-19 | 用户原话 | 略缩小兵牌，缩小时先聚合再隐藏，用同地点的多个单位验收 | [原稿 §16](specs/2026-11-17-worldsim-architecture-design-source.md)、[查看器专题](specs/2026-11-19-readonly-map-viewer.md) | 有效，补充 D-022 |
| D-024 | 2026-11-19 | 用户原话 | 同地的同级顶层单位分别出牌，下属编制在各顶层单位内部显示，再缩小兵牌 | [原稿 §17](specs/2026-11-17-worldsim-architecture-design-source.md)、[查看器专题](specs/2026-11-19-readonly-map-viewer.md) | 有效，细化 D-023 的验收样例 |
| D-025 | 2026-11-19 | 用户原话 | 在浏览器内独立窗口用结构图查看顶层单位的编制 | [原稿 §18](specs/2026-11-17-worldsim-architecture-design-source.md)、[查看器专题](specs/2026-11-19-readonly-map-viewer.md) | 有效，调整 D-022 的编制查看位置和布局 |
| D-026 | 2026-11-19 | 用户原话 | 在现有欧洲地图上构建可玩的 1936 剧本，先接外部地图与剧本导入，通过程序工具建设世界并验收 Agent 推演 | [原稿 §19](specs/2026-11-17-worldsim-architecture-design-source.md)、[推演契约](specs/2026-11-19-1936-europe-agent-turns.md) | 有效 |
| D-027 | 2026-11-19 | 用户原话 | 1936 剧本与后续推演尽量贴近史实 | [原稿 §20](specs/2026-11-17-worldsim-architecture-design-source.md)、[推演契约](specs/2026-11-19-1936-europe-agent-turns.md) | 有效 |
| D-028 | 2026-11-19 | 用户原话 | 决策人读取 GM 管理的可见信息并提交决策，GM 统一推演并生成下一回合 | [原稿 §20](specs/2026-11-17-worldsim-architecture-design-source.md)、[推演契约](specs/2026-11-19-1936-europe-agent-turns.md) | 有效，细化后续决策人权限 |
| D-029 | 2026-11-19 | 用户原话 | 提供调试显示，供人和 Agent 查看及补齐地图缺项 | [推演契约 §7](specs/2026-11-19-1936-europe-agent-turns.md#7-边界复核归属调试与师级序列) | 有效 |

## 仍待裁定的工程问题

以下不是用户裁定，不能引用为已定事实：

1. 首版查看器采用 WSL 内服务、Windows 浏览器和服务端预渲染。后续渲染加速根据实测决定。
2. 查看器的其他部署形态根据跨平台实测决定。
3. ORBAT 模板 / 初始编成 / 运行时 `UnitInstance` 的正式边界（D-010 只定了“一个节点一个单位只有一个最终态”）。
4. 通用 Area 与州/战略区域是否允许重叠及各自语义。
5. `world/` 原生存档在其他操作系统及文件系统上的持久化行为；当前分块和旧节点 hash 兼容实现见专题设计 §11.7，SQL 范围已由 D-018 裁定。
6. 地图尺寸/逻辑坐标范围/初始 POC 规模。
7. 首版是否引入 Docs；INFO 已确定可承载长短文本，Docs 的边界需要重新划分。
8. GM 的“可以做任何事”是否需要保留审计/回执/不可抵赖日志；建议保留，但需用户确认。
9. `INFO` 多包格式的 namespace/type registry/版本化/格式化协议。
