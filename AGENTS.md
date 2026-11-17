# AGENTS.md —— WorldSimulator 常驻索引

> **更新时间**：2026-11-17（文档系统初始化）
> **本文件只做三件事**：项目简介、常驻红线、文档与目录路由。
> **设计正文不在这里**：项目设计看 `docs/superpowers/**`，维护规则看 `docs/AGENTS.md` 与 `docs/agents/**`。
> **禁止用 `@` 导入长文**；按需打开链接。

## 0. 冲突顺序

1. **用户当次明确指令**（逐字优先）。
2. **项目设计裁定**：
   - 用户设计原稿：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`
   - 专题设计：`docs/superpowers/specs/**`
   - 设计索引：`docs/superpowers/DESIGN_RULINGS.md`
3. **项目维护裁定**：`docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`。
4. **当前有效目标设计 / 契约**（先看文件头的状态与取代关系，不按文件名日期猜）。
5. **本文件**（常驻路由与红线）。
6. **`docs/agents/STATUS.md`**（只表示当前事实和进行中状态，不替代设计或维护裁定）。
7. 历史调研、旧草案、历史 status（只作溯源，不作开工依据）。

> 设计裁定 ≠ 维护裁定。设计问题写 `docs/superpowers/**`；文档/门禁/工作流问题写 `docs/agents/**`。同一条规则只保留一个主正本，另一侧放链接。

## 1. 项目简介

WorldSimulator 是一个 **Agent 导向的世界模拟器/编辑器**。它的目标是：

- 用 **原生 polygon 的不规则省份地图**替代 SimulatorMosire 的细化六边形地图；
- 采用 HOI4-类似但独立实现的 **省份、州/区域、战略区域、补给中心、铁路、邻接**规范；
- 使用 **可扩展 INFO + 编制编辑器格式（ORBAT）** 表达实体信息、军队编制与态势，不移植 HOI4 军事系统；
- 使用 **Simos 风格的时间线**（分支 + tick、增量状态、节点地址、检查点）管理世界；
- 基于 **ProjectMosire / AgentLib** 封装 Agent 可编辑、可导出、可回放的 MCP 接口；当前只上 GM，DM 后续。

参考项目：

- `~/SimulatorMosire`：文档系统、地图/时间线/INFO/工具面的主要参考；**代码当前无根 LICENSE，只作行为参考，未经确认不要整段复制**。
- `~/ProjectMosire/AgentLibMosire`：`AgentTool`、MCP server、权限/审批、决策包、上下文与 Docs 契约的上游库。
- `~/ProjectMosire/DocsMosire`：自然语言文档存储；领域模块只存结构化数据和文档地址。
- DSH 现有 `orbat_*` 编排工作台：ORBAT 格式、批量编辑、修订、导出损失报告和证据状态的一手参考。

**明确非目标**：

- 不支持 HOI4 mod 导出；HOI4-like 只指地图/空间/补给规范。
- 当前只实现/开放 GM 面；DM/决策人权限后续再做。
- 人类客户端主要是查看器，不做复杂编辑器；运行环境是 WSL，渲染选型优先跨平台。

## 2. 常驻设计红线

1. **所有查询最终解析为稳定实体。地址是定位方式，ID 是身份。**  
   `tree1-360/map/province/123` 是地址；`province:123` / 内部 UUID 才是稳定身份。

2. **所有修改最终表示为 `Command → ChangeSet → TimelineNode`。**  
   不存在绕过该路径的写入口；时间线节点是 `(branch, tick)`，不重新引入 `revision` 作为节点版本。

3. **领域模块只拥有自己的数据。**
   - 地图拥有省、州、战略区域、补给中心、铁路、地形；
   - 军队/编制拥有编制树、人员/装备、部署、移动、战斗；
   - INFO 拥有感知/决策信息条目；
   - 文档系统拥有所有自然语言正文；
   - 时间线拥有状态引用、分支、增量和检查点。

4. **自然语言与结构化数据分离。**  
   领域模块只存结构化事实与地址引用；战报、说明、设定、历史叙述等正文由 DocsMosire 保存。INFO 的 `note` 不承载长篇正文。

5. **不得把生成假设写成事实。**  
   资源、人口、装备、编制、归属、战斗数据必须记录 `source` / `status` / `confidence`；没有证据就不补造“看起来合理”的数字。

6. **Agent 工具面按角色和视野收窄。**  
   GM 才能改地图/时间线；DM 只能读自己的 INFO、编辑/提交自己的编制或经审批的军队动作。工具权限不能靠提示词约束。

## 3. 文档路由

| 需要找什么 | 入口 |
|---|---|
| 当前状态、未验证项、下一步 | `docs/agents/STATUS.md` |
| 文档系统规则（设计/维护分账） | `docs/AGENTS.md` |
| 维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` |
| 设计索引 | `docs/superpowers/DESIGN_RULINGS.md`、`docs/superpowers/AGENTS.md` |
| 用户设计原稿 | `docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md` |
| 地图/ORBAT/INFO/时间线专题设计 | `docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md` |
| 邻仓与 DSH ORBAT 调查证据 | `docs/superpowers/reports/2026-11-17-neighbor-projects-recon.md` |
| 上一轮 HOI4 社区调研 | `docs/hoi4-community-map-resources-survey.md`（历史调研输入，不是现行设计正本） |
| 上一轮 Agent 世界模型草案 | `docs/agent-world-model-draft.md`（历史草案，见新专题设计） |

## 4. 当前工程边界（草稿，待实现确认）

建议模块名（当前实现仍为单模块 Maven 工程，后续可按边界拆分）：

```text
worldsim-core        # 时间线、Command/ChangeSet、节点地址、存储、回放
worldsim-map         # 原生 polygon 省、州/区域、补给中心、铁路、地图工具
worldsim-orbat       # ORBAT 文档/编制树/实例/部署/导出
worldsim-info        # 决策人 INFO 覆盖层、受众可见性
worldsim-docs        # 或直接依赖 DocsMosire；自然语言文档
worldsim-ga-spi      # AgentTool 收窄、GM/DM 角色（当前仅 GM）、窄读写口
worldsim-app         # 组合根、MCP server、工具装配、审批桥接
```

首版不要一次性实现完整政治/经济/军事模拟；先把 **地图编辑 + ORBAT + INFO + 时间线 + MCP 导出** 做成可验证闭环。

## 5. 开工/收工纪律

- 动手前先读 `docs/agents/STATUS.md` 和对应专题设计。
- 改持久状态、工具面、数据所有权、时间线语义、数值公式前，先落设计文档。
- 没跑过的验证不能写“通过”；写 `NOT_RUN` / `未验证` / `BLOCKED`。
- 发现文档矛盾：记录双方路径、日期、事实；能判定的加“取代”头；不能判定的列入待裁定。
- 本文件只做路由，不写当前状态快照。
