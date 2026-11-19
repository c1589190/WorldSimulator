# MAINTENANCE_RULINGS.md —— 项目维护裁定

> **状态**：有效（2026-11-17 起）
> **用途**：记录文档系统、门禁、协作、工作树、提交等**项目维护**裁定的逐字原话与落点。
> **不记录**：地图/ORBAT/INFO/时间线的业务设计；那些写 `docs/superpowers/specs/**`。

## M-001 文档规范采用 SimulatorMosire 的分账结构

**日期**：2026-11-17  
**用户原话（逐字）**：

> 「你可以进一步调查参考一下隔壁SimulatorMosire（把文档规范抄过来）」

**落点**：

- 采用“项目设计 ≠ 项目维护”两条轴；
- 设计正本放 `docs/superpowers/specs/**`，维护规范放 `docs/AGENTS.md`、`docs/agents/MAINTENANCE_RULINGS.md`；
- 当前状态唯一入口为 `docs/agents/STATUS.md`；
- 用户原话逐字保留，日期用实际日期；
- 旧文档不删除，用状态头 + 取代者标记；
- 没跑过的验证写 `NOT_RUN` / `未验证` / `BLOCKED`，不写“通过”。

**边界**：本文只记录维护裁定，不把 SimulatorMosire 的业务设计当作 WorldSimulator 的业务设计。

## M-002 记录 1936 剧本史料查证路径

**日期**：2026-11-19
**用户原话（逐字节选）**：

> 期间为贴近而查询资料的路径和方法都落文档，后续可能会总结成skill

1936 剧本的史料查证在 `docs/superpowers/reports/` 留下可复查记录。记录检索词和工具参数、来源位置与版本、提取和校核方法、证据冲突及落到的项目地址。首次记录见 [1936 史料查证](../superpowers/reports/2026-11-19-1936-source-research.md)。设计裁定见[用户原稿 §20](../superpowers/specs/2026-11-17-worldsim-architecture-design-source.md)。
