# 军事经济与 AgentLib 接入调查

> **状态**：调研输入
> **日期**：2026-11-20
> **本文性质**：调查证据与工程建议
> **源码基线**：`faac654` 与当日工作树中的事件时间线、地图条件及行军箭头。
> **用户方向**：[设计原稿 §22](../specs/2026-11-17-worldsim-architecture-design-source.md#22-军事经济范围与库存口径)
> **后续实施**：[军事经济首版契约](../specs/2026-11-20-military-economy.md)

## 现有代码

- WorldSimulator 依赖 `io.mosire:agentlib-mosire:0.2.5`。`WorldTools` 以 `AgentTool` 声明工具、JSON Schema、角色和资源权限，注册到 AgentLib 的 `ToolRegistry`。`WorldRuntime` 调用 `AgentToMcpServer.start(...)` 统一通过 stdio 提供 MCP，启动时绑定当前世界的 GM 会话。核对了实际依赖 jar，MCP 调用经 `ToolCallAuthorizer`。WorldSimulator 处理地址解析、领域校验、草稿和时间线提交。[依赖](../../../pom.xml#L23) · [工具声明](../../../src/main/java/io/mosire/worldsim/app/WorldTools.java#L106) · [运行入口](../../../src/main/java/io/mosire/worldsim/app/WorldRuntime.java#L30)
- `CountryState` 的各省条目可分别记录 `owner` 与 `controller`。通行和补给使用实际控制，缺少控制时目前回退到归属。国家已可记录资料和通行许可，尚无人力池。[国家数据](../../../src/main/java/io/mosire/worldsim/country/CountryState.java#L22) · [控制和通行](../../../src/main/java/io/mosire/worldsim/country/CountryState.java#L99)
- `putRailway` 可录入运营状态、日吨位及证据。`world_supply_status` 计算首都经国内铁路和补给中心的可达性，跳过已确认停运的线路。经济期、运力配额、路段用量和补装交付尚未实现。[铁路录入](../../../src/main/java/io/mosire/worldsim/app/WorldTools.java#L166) · [铁路模型](../../../src/main/java/io/mosire/worldsim/map/Railway.java#L10) · [补给可达性](../../../src/main/java/io/mosire/worldsim/app/LandMarchRules.java#L57)
- ORBAT 的实编节点已有直属人员、装备清单及数量证据。`resolveBattle` 能按实编子节点和装备条目写入战损。现有差额查询限于直属数量，通用的全树盘点、独立扣装、调拨与机关库存尚未实现。[装备清单](../../../src/main/java/io/mosire/worldsim/orbat/OrbatDocument.java#L52) · [实编节点](../../../src/main/java/io/mosire/worldsim/orbat/OrbatDocument.java#L108) · [战损定位](../../../src/main/java/io/mosire/worldsim/app/WorldBattle.java#L150) · [直属差额](../../../src/main/java/io/mosire/worldsim/app/WorldQueries.java#L79)

## 建议的结算规则

**国家。** 法定归属作为小范围资料保留证据。运行中沿用现有控制与通行查询。人力池记录本国可用于补充的人员数。补员一次扣减人力池、增加目标实编人数。战损记在实编，后续补员才消耗人力池。

**装备。** 军队领导机关按机关保存负责补充的实物库存，有独立身份和发货地点。单位已领装备直接记在各实编节点的直属清单。查看一个部署单位时，汇总其实际编制子树，同时显示覆盖与未知项。机关储备和其直属部队已领装备分别按持有者查询。已经入库的实物余额跨经济期延续。

**运力。** 将国家的运力视为当期可分配的军事运输额度。经济期由剧本时钟界定，期间可以有多个时间线节点。开启新经济期时计算新额度，期末剩余量到期，已完成分配和使用量可以回放。一次时间推进跨越多个经济期时按时刻换期，分支从分叉节点继承当期已用量。建议首版由 GM 录入当期军事份额，按照能查证或明确标为剧本参数的路线容量核对调拨。共用同一物理路段的运输合计扣该段运力，线路状态和有效控制在结算时校验。日吨位要按期间换算，装备数量按明确的每件运载成本换算成相同单位。来源缺失时显示未知，由 GM 补录资料或给该剧本设定参数。[现有事件推进](../../../src/main/java/io/mosire/worldsim/app/WorldEventTimeline.java#L34)

**批量扣减。** 建议默认在选定实编子树内按持有该装备的节点等额扣减，直属持有的装备也在范围内。库存不足的节点扣到零，余量继续由其余节点均摊，零头按稳定 ID 顺序处理。例如 A、B、C 分别有 2、5、9 件，要扣 9 件，预览应列出各扣 2、4、3 件。指定下属节点时只扣该节点直属数量。未知照原样保留。可确认的合计不足时整笔退回。这套实编数量变更规则可以供战损和日常扣装复用。

**调拨。** 首版以当期实际完成的交付为对象。一次结算同时扣机关实物、计入当期铁路用量，并把装备加到收到装备的实编节点。预览列出三处变动与限制原因。日后若需要安排跨期运输，再加发运与抵达事件。

## 工具面与验收

以下名称用于说明建议的工具粒度。

| 建议的 AgentTool | 行为 |
| --- | --- |
| `world_rail_capacity` | 按节点查询当前经济期、国家军事配额、路段可用量与来源。 |
| `orbat_inventory` | 查询机关库存、单个实编节点直属装备或部署单位全树，返回覆盖及装备稳定 ID。 |
| `orbat_deduct_equipment` | 按实编节点精确扣减，或按部署单位在子树内均摊。预览及应用返回逐节点变动。 |
| `orbat_transfer_equipment` | 预览或应用机关到实编节点的交付，同时结算运力。 |
| `orbat_reinforce_personnel` | 预览或应用国家人力池到实编节点的补员。 |

这些工具建议注册到同一个 AgentLib `ToolRegistry`。地址沿用同一节点的国家、部署单位和编制节点地址，再定位稳定 ID。写工具委托现有草稿应用服务，复用基点 hash、草稿版本、requestId、预览与 `Command → ChangeSet → TimelineNode`。GM 执行结算，决策人后续通过获准的查询和决策接口参与。[地址契约](../specs/2026-11-19-unit-address-and-orbat-format.md#2-地图和单位模块) · [现有写入工具](../../../src/main/java/io/mosire/worldsim/app/WorldTools.java#L80) · [GM 与决策人](../specs/2026-11-19-1936-europe-agent-turns.md)

建议先做实编盘点和两种扣减，再接机关库存、人力池和当期运力。用两个演练部队争同一段铁路，验收共享额度、跨经济期到期、库存守恒、批量不足时原子退回，以及重启后结算回执可重放。核查旧存档能继续读取，缺少库存或额度记录时显示未配置。首版尚待决定经济期长度、剧本运载单位和分配规则。

现有 MCP 实测命令 `mvn -q -Dtest=WorldMcpProcessTest,WorldApplicationServiceTest#agentlibEnforcesRoleAndResourceAtCallBoundary test`，rc=0，2 项通过。覆盖真实 stdio 工具发现、预览、保存、重启重试与 AgentLib 角色和资源权限判定。军事经济行为未实现，以上验收 `NOT_RUN`。[MCP 进程测试](../../../src/test/java/io/mosire/worldsim/app/WorldMcpProcessTest.java#L26) · [权限测试](../../../src/test/java/io/mosire/worldsim/app/WorldApplicationServiceTest.java#L152)
