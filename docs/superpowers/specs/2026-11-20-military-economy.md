# 实编库存、机关调拨与当期铁路运力

> **状态**：有效（首版实现契约）
> **日期**：2026-11-20
> **本文性质**：专题设计
> **依据**：[用户原稿 §22](2026-11-17-worldsim-architecture-design-source.md#22-军事经济范围与库存口径) · [代码调查](../reports/2026-11-20-military-economy-and-agentlib.md)

2026-11-20 用户原话（逐字）

> 那就开始实现？

## 1. 首版范围与数据所有权

国家域保存可补员人力以及军用铁路额度和当期用量，地图域保存线路、运营状态和日吨位。ORBAT 保存每个领导机关的补充库存、所在省及实际编制各节点的直属装备。归属资料及实际控制沿用既有国家字段。铁路交付按实际完成时刻记账，生产、在途和跨国装备交付可单独扩展。

## 2. 身份、查询与结算

- `<node>/orbat/unit/<id>` 盘点实际编制子树。`<node>/orbat/doc/<doc>/node/<id>` 读直属数量。`<node>/orbat/depot/<id>` 读机关库存。装备按稳定 `itemId` 匹配，盘点保留未编码装备、未知条目和覆盖情况。指定下属节点写入时同时给单位地址，校验子树归属。
- 精确扣装作用于指定节点直属库存。均摊扣装从整个所选实际编制子树中分配，按持有节点等额扣件数，不足的节点扣到零后重新均摊。小于参与节点数的零头按稳定节点 ID 顺序分配。预览返回每个节点的扣减前后数量。已知且已登记的估计数量均可作为剧本账面数，记录数量的证据状态。未知保持未知。完整清单中缺项可视为零。补装或补员的目标数量未知时，先由 GM 确认实编基数。
- `configureRailEconomy` 用剧本时钟的当前时刻锚定第一期，期长为配置的正整数分钟。时间推进到期边界时清空当期用量。一个经济期可以覆盖多个时间线事件。事件时间线在换期时保存该时刻，换期与抵达或裁决处于同一时刻时共用节点。分叉继承基点用量。已有经济期的锚点和期长固定，GM 可按键更新国家额度和装备重量，原有的其他条目及已用量沿用。旧式草稿在保存的目标时刻体现对应经济期和用量。
- 装备重量和运力统一记整数公斤。用有证据的国家期额度、单位装备运输重量和 `Railway.Operation.tonnesPerDay` 计算。日吨位乘经济期占一天的比例，向下取整到公斤。同一普通陆地邻接的两向及重叠铁路共用一个路段额度，采用有明确开放状态及吨位的记录中的最大值。全关闭供给量为零，其他缺证据情况显示未知。国家用量按交付重量计一次，每一经过路段分别计交付重量。查询返回所选路线的路段余量和限制原因。
- 同国机关向部队交付。跨省需给出从机关驻省到部队当前驻省的陆省链，以结算时的地图、有效控制、通行和铁路容量核对。机关和部队同省可直接交付。部队在两省之间行军时按抵达后的驻省交付。出库、占用额度和实编入账是一笔变更。补员同时修改国家池和实编人数。

## 3. GM 工具和持久化

| 接口 | 用途 |
| --- | --- |
| `orbat_inventory` | 盘点实际编制或机关库存，可返回逐节点记录。 |
| `world_rail_capacity` | 从国家地址查询经济期、配额、已用量和可选路线余量。 |
| `orbat_deduct_equipment` | 使用草稿预览或应用精确、均摊扣装。 |
| `orbat_transfer_equipment` | 使用草稿预览或结算机关交付。 |
| `orbat_reinforce_personnel` | 使用草稿预览或结算人力池补员。 |
| `edit_apply` | 录入 `putCommandDepot`、`setManpowerPool`、`configureRailEconomy`，并支持与其他命令批量执行扣装、交付、补员。 |

AgentLib 统一注册和鉴权。写入继续使用草稿版本、基点 hash、requestId 与 `Command → ChangeSet → TimelineNode`。数量及运载参数至少记录来源和证据状态。变更记录保存实编、机关库存、人力池及运力的结算前后值。预览和失败批次不消耗余额，请求重试复用已保存回执。旧存档中的经济资料缺项保持空缺，沿用旧节点的地址和哈希验证。

### 初始化和调用

1. 用 `edit_open` 打开剧本草稿。已有事件节点时，先用 `advanceTime` 选定新的结算时刻。没有剧本时钟时先用 `setSimulationTime` 初始化。
2. 用 `edit_apply` 写入设置。`putCommandDepot` 的 `depot` 对象含 `id`、`name`、`countryId`、`provinceId`、`equipment` 和 `evidence`，其中 `equipment` 采用 ORBAT 库存格式。`setManpowerPool` 接受 `countryAddress`、`available`、`evidence`。`configureRailEconomy` 接受 `rules`，其中 `periodMinutes` 为正整数，`countryQuotas` 以国家 ID 为键，`itemWeights` 以装备 ID 为键，两者的值均为 `{kilograms, evidence}`。`putRailway` 的 `operation` 提供开放状态、`tonnesPerDay` 及证据。铁路容量和预算参数可明确标为剧本估算。
3. 写入工具均接受 `draftId`、`expectedVersion`、`requestId`、`evidence`，可用 `dryRun: true` 查看结果。扣装和调拨需要 `unitAddress`、`itemId`、`count`，调拨另给 `depotAddress` 和跨省时的 `route`。补员需要 `countryAddress`、`unitAddress`、`count`。三者都可指定 `formationAddress`。同一工具的预览与应用用各自的请求 ID。用 `edit_save` 发布节点。
4. 用 `orbat_inventory` 的 `address` 查询库存，`details: true` 展开逐节点记录。用 `world_rail_capacity` 的 `countryAddress`、可选 `route` 与 `itemId` 查询路线余量及 `piecesByCapacity`。该件数由运力和单件重量计算，调拨预览还会核对机关库存和收货基数。已保存的结算变化可从 `timeline_diff` 查看。

## 4. 验收

以带明确演练证据的两个同级部队验收实编汇总、精确及均摊扣装、未知数、局部缺货或超额时批次回滚。验收相反方向运输和不同国家在重叠线路间共享同一路段、经济期剩余额度到期及装备跨期保留。通过 GM MCP 在保存、分叉和重启后核对同一地址的数量及结算回执。史实数量仍按相应资料独立录入。
