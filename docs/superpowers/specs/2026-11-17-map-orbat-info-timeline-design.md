# 地图 + ORBAT + INFO + Simos 时间线：可行性与专题设计

> **状态**：待用户评审（工程建议，2026-11-17）  
> **本文性质**：专题设计 + 可行性分析；不是最终实现计划，也不是用户裁定。  
> **本文取代**：`docs/agent-world-model-draft.md` 作为当前架构草案；旧调研保留为背景输入。  
> **用户设计原稿**：[`2026-11-17-worldsim-architecture-design-source.md`](2026-11-17-worldsim-architecture-design-source.md)  
> **邻仓/DSH 侦察证据**：[`../reports/2026-11-17-neighbor-projects-recon.md`](../reports/2026-11-17-neighbor-projects-recon.md)  
> **冲突口径**：用户当次指令 > 用户设计原稿 > 本文；本文中的工程建议若被用户否定，应追加新设计/取代头，不静默改代码。

---

## 0. 结论先行：可行，但不能“三套模型硬拼”

**可行。** 地图、ORBAT、INFO、时间线四者并不冲突，前提是承认它们回答的是不同问题：

| 层 | 回答的问题 | 真值/职责 |
|---|---|---|
| 地图 | 世界在哪里？地形、邻接、州/区域、补给节点如何？ | 空间与基础设施真值 |
| ORBAT | 军队如何编成？谁隶属谁？编制人员/装备是什么？ | 组织与授权编成真值 |
| INFO | 某个决策人此刻知道什么、做了什么决定？ | 感知/决策覆盖层，不是世界真值 |
| 时间线 | 世界在哪个分支、哪个 tick？如何从旧状态到这里？ | 状态引用、增量、回放、分叉 |
| 文档 | 战报、设定、说明、叙事是什么？ | 自然语言正文真值 |
| MCP/AgentLib | Agent 如何安全地查询、预览、提交、导出？ | 工具/权限/审批边界 |

最危险的做法是把 ORBAT 节点直接当运行时单位、把 INFO 当数据库、把地图像素当状态差异、把时间线做成每模块各一套 revision。  
推荐做法是：**地图提供位置与空间约束；ORBAT 提供编制与装备；运行时单位实例是二者的桥；INFO 只引用地址；时间线统一记录所有结构化变更。**

可行性的核心结论：

1. **地图层可以独立重写**，而且比在 SimulatorMosire 里改 hex 更便宜：当前 `HexCoord` 在 473 个 Java 文件中出现，横跨 map/unit/army/economy/app 等模块；原地把 hex 换成 province 会拖垮整个仓。
2. **ORBAT 可以直接兼容 DSH 的 `mil-org` 格式**，但必须拆分“编制模板/授权编成”和“运行时单位实例”，否则无法表示战损、移动、补给和当前状态。
3. **INFO 可以作为决策人感知层**，支持任意地址、短文本、长文本、自定义 typed payload 与多包扩展；它仍不是地图/ORBAT 的世界真值。
4. **Simos 风格时间线适合作为统一状态机**，但必须把“节点 = branch + tick”真正落地；当前 Simos 代码里仍有 revision 表，不能直接假定它已经完成新口径。
5. **MCP 封装是合适的**，但工具面要少而深、当前只上 GM 面、所有写操作预览 + 原子提交 + 审计；DM/决策人权限后续再启。
6. **HOI4 mod 导出不是目标**。HOI4-like 只指地图/空间/补给规范；不再设计 BMP/CSV/州文件导出。

---

## 1. 推荐总体结构

```text
┌───────────────────────────────────────────────────────────────┐
│ Agent (当前仅 GM；DM 后续)                                    │
└──────────────────────────────┬────────────────────────────────┘
                               │ MCP tools
┌──────────────────────────────▼────────────────────────────────┐
│ worldsim-app / AgentLib bridge                                │
│  ToolRegistry · AgentTool · ToolCallAuthorizer · ToolGate     │
│  DecisionPackages · DocsMosire · artifacts / approvals        │
└──────────────────────────────┬────────────────────────────────┘
                               │ WorldTool / WorldToolSource
┌──────────────────────────────▼────────────────────────────────┐
│ WorldSimulator domain modules                                 │
│                                                               │
│  worldsim-map       province grid, states, regions, supply    │
│  worldsim-orbat     ORBAT template/catalog/deployment         │
│  worldsim-info      INFO entries + audience visibility        │
│  worldsim-core      command, changeset, branch/tick, replay   │
│  docs adapter       natural-language documents                │
└──────────────────────────────┬────────────────────────────────┘
                               │ one state tree
┌──────────────────────────────▼────────────────────────────────┐
│ Timeline state                                                 │
│  branch: tree1 ── tick 0 ── tick 1 ── tick 2 ...              │
│  branch: tree2 ── tick 0 ── tick 1 ... (fork from any node)    │
│  node = (branch, tick) + ChangeSet + optional checkpoint       │
└───────────────────────────────────────────────────────────────┘
```

**关键边界**：

- 地图不认识 ORBAT/INFO 的业务字段；地图只知道省份 ID、位置和基础设施。
- ORBAT 不认识地图像素；只知道部署的 province ID 和空间约束。
- INFO 不认识地图/ORBAT 的内部结构；只保存地址、key/value、受众和来源。
- Core 只负责组合、时间线、命令/ChangeSet/节点和持久化，不重新实现领域规则。
- App/AgentLib 层只负责装配、角色、审批、MCP、artifact 和视图，不承载地图/ORBAT 的领域规则。

---

## 2. 地图：HOI4-like，而不是 HOI4 复制品

### 2.1 原生 polygon 为权威

用户已裁定：**省份原生支持 polygon，polygon 是权威几何**。不再采用“栅格省份为权威”的首版建议。

推荐模型：

| 表示 | 角色 | 是否进状态/ChangeSet |
|---|---|---|
| `ProvinceShape` | 权威几何：一个或多个 polygon part（外环 + 洞） | 是 |
| `displayPoint` | 省份/实体的显示坐标；前端不必绘制完整 polygon | 是（随省/实体） |
| `ProvincePolygonCache` | 渲染/空间查询用的派生缓存 | 否（可重建） |
| `AdjacencyGraph` | 由共享 polygon 边界推导的省邻接 | 是（可持久化校验结果） |
| `EntityLink` | 用稳定标识符（province/supplyHub/unit/...）指向 polygon 或显示坐标 | 是 |

**关键设计**：

- 地图实体按稳定标识符分类和索引，例如 `province:<id>`、`state:<id>`、`region:<id>`、`supplyHub:<id>`；
- 每个实体可链接到 `ProvinceShape`（或一个显示坐标）；
- 人类查看器**可以不渲染 polygon**：只在 `displayPoint` 画标记，选中时画单位/省的轮廓；
- polygon 仍是权威：命中测试、邻接推导、面积/边界、导出、校验都使用它；
- 前端规模不取决于 polygon 顶点数，而取决于当前视口/选中对象/请求的 tile。

> 这满足“减少前端规模”的目标，同时不在数据层丢失真实几何。

### 2.2 坐标、世界范围与显示坐标

- 地图使用逻辑平面坐标（`Vec2(x,y)`），不绑定经纬度或像素。
- `WorldMap` 有一个逻辑范围 `width × height`，用于视口和边界校验；不需要为了 HOI4 导出强制 256 倍数。
- 每个省/实体有一个 `displayPoint`，用于标签、单位标记、选中轮廓锚点。
- 多边形坐标可以很大；渲染/命中查询时再简化、裁剪、变换。不要在状态里存渲染缓存。
- 首版 POC 可以用较小的逻辑范围（如 10000×5000）和 200–500 个 polygon 省；以后可换真实地理数据。

### 2.3 核心实体

```text
Province
  id              稳定实体 id（字符串）
  name
  kind            land | sea | lake
  terrain         词表由 TerrainCatalog 唯一声明
  shape           ProvinceShape：多 part polygon + holes
  displayPoint    显示坐标（标签/单位标记/轮廓锚点）
  stateId?        land/lake 可属于州；sea 通常无州
  regionId        战略区域 id
  tags            可选结构化标签

State
  id
  name
  provinceIds     严格集合；land province 归属关系由 validator 校验
  category?       若以后需要建筑/资源槽
  resources?      动态/静态资源点先分开，别塞进 state 定义
  owner?          动态状态，不属于静态地图定义

StrategicRegion
  id
  name
  provinceIds
  navalTerrain?   可选
  weather?        可选，后续动态模块

SupplyHub
  id
  provinceId      必须指向 land province
  level
  capacity?       可选
  displayPoint?   可由 province 的 displayPoint 派生

Railway
  id
  level
  provincePath    连续且每一对相邻
  stateIds?       派生

EntityLink
  key             如 province:berlin / supplyHub:rhine / unit:blue-1
  category        province | state | region | supplyHub | unit | custom
  targetId        polygon/实体 id
  displayPoint?   可选：覆盖实体自身显示坐标
  tags
```

**州/区域关系**：

- `land province` 属于恰好一个 `State`；
- `State` 属于恰好一个 `StrategicRegion`；
- 每个 `Province` 属于恰好一个 `StrategicRegion`；
- 额外 `Area`/overlay 可用于文化区、历史区、剧本标签，允许重叠；不要把它和 `StrategicRegion` 混为一谈。

**地址与实体链接**：

- 地址遵守 Simos 严格格式：`treeXXX-tickXXX[/<type>[/<id>...]]`；
- 地址是定位方式，稳定 id 是身份；
- `EntityLink` 可用同样 id 定位 polygon/显示坐标，不把“渲染所需顶点”复制进实体记录；
- 任意地址（包括未来 INFO 的自定义地址）只做严格语法校验，是否存在由 resolver 决定。

### 2.4 空间、邻接与补给

- **普通邻接**：由 polygon 的共享边界推导（JTS boundary intersection length > epsilon）；不是 4 邻域像素邻接。
- **特殊邻接**：海峡、运河、不可通行、规则连接单独存表，不要硬塞进普通邻接。
- **河流/道路/铁路**：作为独立线/图实体，引用 province 序列；不要混入 province polygon 的顶点。
- **补给（首版规范）**：
  - `SupplySource`：首都/港口/仓库/补给站，位于某个 province；
  - `SupplyHub`：省级设施，字段至少 `id / provinceId / level / capacity / range`；
  - `Railway`：省链 `provincePath` + `level`，每个相邻对必须在普通邻接中存在；
  - `Road` / `River`：可选运输/障碍层；首版可以只做 rail + 基础道路；
  - `SupplyArea` 是派生视图，不允许手工写入一个与网络无关的“补给区”；
  - 动态 `supplyStatus` 只作为时间线状态/派生结果，不写回静态地图；
  - 首版算法只做“源 → 铁路/道路可达 → hub 容量/距离衰减”，不要直接抄 HOI4 公式；算法放在 `SupplyModel` 接口后，便于替换。
- **补给最小不变量**：
  - hub 必须在 land province；
  - railway 的每个相邻省对必须真实相邻；
  - railway/hub 引用必须存在；
  - 不可达的 hub/单位应返回 `supply.disconnected` warning，而不是静默视为有补给；
  - 删除省/州时必须处理经过它的 railway/hub，或拒绝操作。
- **海/空**：
  - `StrategicRegion` 是海权/空权/天气的容器；
  - 地图层只提供省、海省和区域归属；任务、航线和战斗结果属于运行时/军队模块。

### 2.5 静态地图校验规则（首版必须做）

参考 EarthToHoI4 的“严格地图”思路，但**不绑定 HOI4 导出**：

- `ProvinceShape` 的每个 polygon part 合法、非空、面积 > 0；
- 不存在自交/重叠省；允许共享边界，不允许面积重叠；
- `displayPoint` 必须落在对应 polygon 内（多 part 时至少在一个 part 内）；
- 每个 land province 属于恰好一个 State；
- 每个 State 属于一个 StrategicRegion；每个 Province 属于恰好一个 StrategicRegion；
- State/Region 的 provinceIds 与 Province 反向引用一致；
- SupplyHub 只在 land province；
- Railway 省链连续、相邻、引用有效；
- 特殊邻接引用存在且类型合法；
- 多边形坐标在世界范围内；
- 地址 `NodeAddress` 语法严格，实体 resolver 严格；
- 不需要/不支持 HOI4 BMP/CSV/coastal/port 校验，因为**不导出 HOI4**。

> 校验是地图包自己的规则，不是 Agent 提示词能替代的；必须由 validator + 测试强制执行。

### 2.6 “抄开源地图”的边界

- **可以抄规范/算法**：省份划分、邻接、州/区域、补给中心、铁路、校验。
- **不建议抄数据**：Mappa Mundi、Blank Map、MapGen 模板、vanilla HOI4 文件通常没有清晰的开源许可，或者包含游戏衍生资产。
- **首版不做 HOI4 导出**；因此不需要复制 HOI4 的 BMP/definition.csv/州文件结构。
- **推荐的数据路径**：
  - 用开放地理数据/自研生成器生成自己的 polygon 世界；
  - 参考 EarthToHoI4 的切分/校验算法（代码 MIT，数据许可另计）；
  - 参考 `hoi4-agent-tools` 的地理模型/生成接口（代码 Apache-2.0，输出仍受内容权利约束）；
  - 做 100% 可分发的小型合成世界作为 POC。

---

## 3. ORBAT：模板、授权编成、运行时单位，三层分离

### 3.1 为什么不能把 ORBAT 树直接当运行时单位

DSH ORBAT 是**编制协作/编辑器格式**，它的强项是：

- 稳定的树结构；
- 人员、装备、上级/下级、备注；
- 每个节点可带证据状态；
- 可批量编辑、预览、导出；
- 与人的网页编辑共用同一工作区。

它不直接提供：

- 当前地图位置；
- 移动、战斗、补给、士气；
- 当前实有人员/装备与授权编成的差额；
- 作战序列随时间的变化；
- 部队与决策人/国家的权限关系。

因此推荐三层：

| 层 | 名称 | 来源/例子 | 变更频率 |
|---|---|---|---|
| 模板层 | `FormationTemplate` | DSH `mil-org` 树、编制模板库 | 低 |
| 授权/初始编成层 | `OrderOfBattle` | 一个战役/国家的初始 ORBAT 文档 | 中 |
| 运行时实例层 | `UnitInstance` | 部署到某省的师/营/连 | 高（每 tick） |

`UnitInstance` 引用 `orbatNodeId`，而不是复制整棵树：

```text
UnitInstance
  unitId             运行时稳定 id
  orbatDocumentId    DSH 编制文档 id / 原生包 id
  orbatNodeId        DSH 节点 id（native 格式；mil-org 导出会丢，见下）
  templateId?        可选模板引用
  parentUnitId?      运行时上级（通常对应 ORBAT 父节点）
  provinceId         地图位置
  status             active | moving | engaged | routed | destroyed | ...
  movement?          路径/进度/目标
  currentPersonnel   当前实有人员（可低于授权）
  currentEquipment   当前实有装备（名称/数量/状态）
  supply            当前补给状态/库存
  decisionMakerId?   指挥/归属的决策人
  infoRefs?          相关 INFO 条目地址
```

### 3.2 与 DSH `mil-org` 的字段映射

DSH 导出的 `mil-org` 根结构（实测）大致是：

```json
{
  "kind": "mil-org",
  "version": 1,
  "app": "军队编制编辑器",
  "name": "...",
  "personnel": 140,
  "equipTotal": 108,
  "data": {
    "name": "青松营",
    "type": "infantry",
    "echelon": "battalion",
    "personnel": 6,
    "equipment": [],
    "children": [
      {
        "name": "1连",
        "type": "infantry",
        "echelon": "company",
        "personnel": 3,
        "equipment": [],
        "children": []
      }
    ]
  }
}
```

映射建议：

| ORBAT 字段 | 内部处理 |
|---|---|
| `name` / 节点 `name` | 结构化名称，保留原文；不要把“示意/推定”写进名称，证据放 `custom.orbat` 或 `remark` |
| `type` | 兵种/职能标签；使用 DSH 类型词表或扩展词表 |
| `echelon` | 层级（army/corps/division/.../individual）；用于树和部署约束 |
| `personnel` | 节点的**本级直属**人数；不是含下属总计 |
| `personnelMax` | 0 = 未指定；不要为了补齐而猜造 |
| `equipment[]` | 本级直属装备名称/数量；需要单独 equipment catalog 解释能力 |
| `children[]` | 上下级树；合计由程序递归计算，不能把含下属总计重复写回上级 |
| `custom.orbat` | 证据状态/来源/观察总计；必须保留 |
| `remark` | **短备注**；长篇说明/出处正文应链接到 Docs |
| `layout/collapsed/stack/customName/mod` | UI/编辑器元数据；可保留但不参与战斗/地图逻辑 |

### 3.3 关键约束

- **`mil-org` 不是无损原生格式**。DSH 当前 `orbat_export` 返回 `lossReport`，会损失内部节点 id、历史/sequence/资源 revision 等。  
  因此内部持久化必须用**原生格式**（保留文档 id、节点 id、custom/evidence、内容版本、资源目录版本），把 `mil-org` 作为兼容导出。
- 建议原生格式 `orbat-native-v1` 至少包含：
  - `documentId` / `contentVersion` / `name`；
  - 递归 `nodes[]`（稳定 `nodeId`、name/type/echelon/personnel/personnelMax/equipment/children/custom/remark/ui）；
  - `custom.orbat` 字段级证据与 `observedTotals`；
  - 资源目录/模板库 revision 引用；
  - 导入来源、导出目标、lossReport（兼容导出时）。
  原生格式用于时间线持久化和内部回放；`mil-org` 只用于 Army_Editor 1.8.0 兼容交换。
- **不要用 `0` 冒充未知**。DSH 的旧数据 0 默认有歧义；未知/空必须显式标注 `unknown` / `legacy-ambiguous`。
- **模板与实有分离**：`personnel`/`equipment` 可以是 initial/authorized/actual/standard/estimate；运行时战损写入 `current*`，不回写模板。
- **装备目录与编辑格式分离**：DSH 的装备只是名称/数量；战斗系统需要 equipment catalog（射程、火力、防护等）。首版可以只做“编制账本”，不做 HOI4 式装备战斗。
- **单位层级与地图位置**：首版建议所有层级都锚定 `provinceId`；下级默认跟随上级（`attached=true` 或 route/offset），但允许独立行动。不要把“军团 HQ 在某省”解释成该省只有一个单位。
- **一个单位、一个节点、一个最终态**（用户裁定）：  
  `UnitInstance` 在同一 `(branch, tick)` 节点只能暴露一个 effective final state；中间修改可存在内部 change/history 里，但查询/导出/战斗读取只能看到最终态。禁止同一单位在同一节点出现两个“当前状态”。
- **部队与决策人**：决策人权限当前未启用；`decisionMakerId` 字段预留在 `UnitInstance`/部署映射，后续再接，不要塞进 ORBAT JSON 破坏兼容。

### 3.4 单位/战斗系统边界

用户明确不采用 HOI4 军事系统。首版不要把 HOI4 的 division template、combat width、equipment archetype 和战斗公式塞进 ORBAT 映射。推荐：

- ORBAT 只回答“有多少人/什么装备/什么层级/谁隶属谁”；
- `UnitInstance` 回答“现在在哪/什么状态/还剩多少”；
- `CombatModel` 是可插拔的独立模块（首版甚至可以只有“接触即停/简单损耗”）；
- 战斗结果写成 `INFO` 条目 + 时间线 ChangeSet，不直接改 ORBAT 模板。
- 以后若要接 HOI4 风格战斗，再写独立的适配层，不要让 ORBAT 格式承担 HOI4 语义。

---

## 4. INFO：决策人的结构化感知层

### 4.1 INFO 是可扩展的信息覆盖层

INFO 的职责是回答：

- 某实体/地址上有什么可读信息？
- 这条信息是什么类型、什么格式、给谁看？
- 它来自哪里、属于哪个节点？
- 它如何被不同包/插件扩展和格式化？

INFO 现在**允许**：

- 任意合法地址（省、州、区域、单位、补给中心、自定义实体）；
- 短文本；
- 长文本；
- 自定义 typed payload；
- 多包、多 namespace、类型注册和格式化。

INFO 仍然**不是**：

- 地图/ORBAT 的世界真值；
- 无来源、无地址的散装公告；
- 可以绕过权限随便写别人实体的通道。

### 4.2 INFO 包与条目

```text
InfoPackage
  namespace        e.g. "core", "battle", "logistics", "mod.foo"
  version          包格式版本
  types[]          类型注册：
    typeKey        e.g. "battle.result", "supply.status", "custom.foo"
    displayName
    schemaId       可选 JSON Schema/自定义 schema 标识
    formatter      可选渲染器/格式化器标识
  entries[]        该包的条目

InfoEntry
  id               稳定 id（包内唯一）
  node             branch + tick（或从所属状态派生）
  address          任意严格地址，如：
                   tree1-360/map/province/berlin
                   tree1-360/map/supplyHub/rhine
                   tree1-360/orbat/unit/blue-1
                   tree1-360/custom/weather/front-1
  typeKey          类型（必须能在包注册表中解析，或明确为 untyped）
  format           text/plain | text/markdown | application/json | 自定义
  shortText?       一行/短文本
  longText?        长文本/markdown/文本块
  payload?         typed JSON/二进制引用
  audience:
    gm             bool（当前 GM-only 阶段默认 true）
    decisionMakers 预留；DM 后续启用
    affiliations   预留；模块自报受众
  source:
    module         map | orbat | army | gm | system | mod
    ref            实体地址/命令 id/文档 id
  visibility       gm | decisionMakers | affiliations | public
  createdAt        审计时间
```

**多包/扩展规则**：

- 每个包有独立 `namespace`/`version`/`typeKey` 命名空间，避免冲突；
- `typeKey` 解析只认已注册类型；未知类型可以保留为 `untyped`，但不能静默格式化；
- 每个 `typeKey` 可注册 schema/formatter；
- 包内容可以按包导出/导入；
- 地址语法统一走严格 `NodeAddress`，任意实体 path 不要求 core 认识；
- 解析实体的 `address` 由对应模块 resolver 完成，INFO 包本身不解释地图/ORBAT 语义。

### 4.3 INFO 可见性与权限（当前阶段）

- **当前只实现 GM 面**：GM 可读全部 INFO、写全部 INFO；
- DM/决策人可见性字段先保留，但不实现权限判定；
- 其他 Agent 默认不可见；
- 后续 DM 启用时：
  - `audience.decisionMakers` 含自己，或 `affiliations` 与自己的归属标签相交，或自己发出；
  - 读工具必须做投影，不能返回后再让模型过滤；
  - 写工具不能伪造他人来源。
- 长文本/短文本都不自动等于“公开”；仍受 address/audience/source 权限约束。

### 4.4 INFO + ORBAT + 地图的结合点

| 场景 | 地图 | ORBAT | INFO |
|---|---|---|---|
| 某营部署到省 123 | 省 123 polygon/显示坐标 | `UnitInstance(orbatNodeId, provinceId=123)` | `address=tree1-360/map/province/123`，`typeKey=deployment` |
| 进攻结果 | 控制权/战损（未来） | 更新 `UnitInstance.current*` | `address=tree1-360/orbat/unit/blue-1`，`typeKey=battle.result`，长文本战报可同条保存 |
| 补给中断 | 省/铁路/枢纽 | 单位补给状态 | `address=tree1-360/map/supplyHub/rhine`，`typeKey=supply.status` |
| 指挥部命令 | 目标省/路线 | 运行时移动/状态 | 命令结构化 payload + 长文本说明 |
| 情报误判 | 地图真值不变 | 编制真值不变 | INFO 存“估计/置信度”，不写回地图/编制 |
| 第三方扩展 | 任意地址 | 任意地址 | 自定义 package/type/formatter |

> 关键原则：**地图/ORBAT 是真值；INFO 是可扩展、可寻址、可格式化的覆盖层。** 长文本可以存在 INFO，但必须挂在明确地址和来源上。

---

## 5. Simos 风格时间线

### 5.1 节点模型

用户已明确采用：

- 一个状态机：时间线；
- 节点地址：`treeXXX-tickXXX`；
- 弃用 `rev` 作为节点版本；
- 同一 `(branch, tick)` 对应一个节点；
- 分支从任意已有节点分叉；
- 增量存储：基线全量，后续节点只存相对父节点的增量；
- 节点查询可落到“某分支某 tick”。

建议实现：

```text
TimelineNode
  branchId          tree1 / tree2 / ...
  tick              非负整数
  parentNode?       父节点地址（创世为空）
  changeSetRef      map/orbat/info 的增量引用
  checkpointRef?    可选，最近的全量/压缩快照
  contentHash       节点内容哈希（用于并发与回放校验）
  createdAt         审计时间
  createdBy         agent / human / system
```

地址示例：

```text
tree1-0
tree1-360
tree1-360/map/province/123
tree1-360/map/state/64
tree1-360/orbat/doc/<docId>/node/<nodeId>
tree1-360/unit/army/<unitId>
tree1-360/info/<infoId>
```

### 5.2 同 tick 多写、working copy 与保存闸（已裁定）

**一个节点 = 一个 `(branch, tick)`**。多次写入按调用顺序 append 到同一个 working ChangeSet，最后作为一个节点保存；不引入 revision，不做 undo。

完整语义：

1. 从**已保存节点**打开 working copy；
2. 在 working copy 内多次 apply：按顺序合并，后写覆盖前写（同字段），没有 undo 栈；
3. `discard`：丢弃内存 working copy，回到打开时的保存快照；不会改动任何已保存节点；
4. `save`：校验当前状态，生成一个新的已保存节点；working copy 的全部 changes 作为该节点的一个 ChangeSet 落盘；
5. **只有已保存节点**能：
   - 在同一分支创建下一节点（前提是它是该分支 head）；
   - 创建新分支（fork）；
6. 未保存 working copy 不是节点，不能被其他查询/分支引用；
7. 并发写入用 `expectedNode` + `expectedContentHash`；不匹配 ⇒ 零写入；
8. 同一 tick 想要另一个版本 ⇒ 新分支，不在同 tick 下造多个 revision。

### 5.3 增量与检查点

```text
tree1-0      全量基线 / checkpoint
tree1-360    相对 tree1-0 的增量
tree1-720    相对 tree1-360 的增量
tree2-600    从 tree1-360 分叉；相对 tree1-360 的增量
```

- 查询节点状态：从最近祖先 checkpoint 读取全量，再沿路径回放增量；
- checkpoint 策略：每 N 个节点、每个分支起点、或显式 `timeline.checkpoint`；
- checkpoint 是纯派生缓存，不是第二真值；丢失时可以从 delta 链重建；
- 增量粒度按模块/实体/字段；地图 polygon 变更存新/旧 polygon 或结构化 geometry patch，不存整张世界快照；
- 节点内容哈希由完整状态规范序列化后计算，用于保存闸、并发校验与回放自检。

### 5.3 增量与检查点

```text
tree1-0      全量基线 / checkpoint
tree1-360    相对 tree1-0 的增量
tree1-720    相对 tree1-360 的增量
tree2-600    从 tree1-360 分叉；相对 tree1-360 的增量
```

- 查询节点状态：从最近祖先 checkpoint 读取全量，再沿路径回放增量；
- checkpoint 策略：每 N 个节点、每个分支起点、或显式 `timeline.checkpoint`；
- checkpoint 是纯派生缓存，不是第二真值；丢失时可以从 delta 链重建；
- 增量粒度建议：按模块/实体/字段，而不是整份世界快照；地图边界变更可用局部 mask 或行段 diff。

### 5.4 历史节点编辑：先只能 fork（已裁定）

- 不提供原地改写历史节点；
- 不提供自动 rebase / 后代重放；
- 修改非 head 节点：必须从该已保存节点 fork 新分支；
- 修改 head：只能保存为该分支的下一节点；
- 旧分支永久保留；
- 未来若要支持 rebase，必须单独设计冲突检查机和用户裁定，不能先偷偷实现。

> 这条是“先只能 fork”的硬约束；不要把“编辑历史”做成覆盖保存。

### 5.5 时间线工具

建议工具组：

- `timeline.tree`：分支/节点树；
- `timeline.node`：读取某节点摘要/内容哈希/父节点；
- `timeline.diff`：两节点之间 ChangeSet；
- `timeline.fork`：从任意节点分叉；
- `timeline.step`：推进固定 tick，生成新节点；
- `timeline.checkpoint`：显式生成/校验检查点；
- `timeline.replay`：验证从 checkpoint + delta 回放一致。

当前只实现 GM 面；DM/决策人后续再开放时间线权限。

---

## 6. AgentLib + MCP 工具设计

### 6.1 使用 AgentLib 的哪些能力

从 `ProjectMosire/AgentLibMosire` 复用：

- `AgentTool`：工具名称、描述、JSON Schema、`spec`、`roles`、`gate`、`resources`、`ledgerArgs`、`execute`；
- `ToolRegistry` / 动态 `ToolSurface`：按每次请求变化工具面；
- `AgentToMcpServer`：stdio / Streamable HTTP MCP server；
- `ToolCallAuthorizer`：工具调用唯一判定入口；
- `ToolGate.Ask` / 审批渠道：敏感写操作必须过人；
- `DecisionPackages`：批量变更的预览、审核、逐条批准、提交；
- `ContextInjection` / `ToolSurfaceNoticePolicy`：工具面变化通知；
- `DocsMosire`：可选文档正文；INFO 自身允许短/长文本与自定义格式，不强制把所有文本迁到 Docs。

### 6.2 建议新增的薄 SPI

不要直接把 `SimosTool`（SimulatorMosire 的领域 SPI）当成 WorldSimulator 的契约；可借鉴其形态，新增：

```java
public interface WorldTool extends AgentTool {
  Set<ToolRole> toolRoles();   // 当前仅 GM；DM 后续
  // 继承 AgentTool 的 resources(), gate(), execute()...
}
```

`WorldTool` 的语义与 Simos 一致：

- `toolRoles()` 是“谁能看见/调用”的唯一拼写点；
- `gate()` 是审批策略唯一入口；
- `resources()` 声明命名空间（map/orbat/info/timeline/docs）；
- 空角色集合 = 装配错误，不要静默放行；
- `execute` 不自行绕过 `ToolCallAuthorizer`。

> 如果决定直接依赖 `simos-ga-spi`，可以把 `WorldTool` 做成 `SimosTool` 的适配层；但不要把 `SimosTool` 的 `map`/`unit` 领域类型拖进新项目。

### 6.3 工具分组与默认工具面

建议首版核心工具约 14 个；地图/ORBAT 高级编辑按 profile 加载。

#### Core（默认，当前仅 GM 可见）

| 工具 | 作用 | 角色 |
|---|---|---|
| `world.overview` | 世界/节点/地图/ORBAT/INFO 摘要 | GM |
| `world.lookup` | 按地址/名称/ID 查实体 | GM |
| `map.tile` | 返回查看器 artifact（不要求网页） | GM |
| `map.inspect` | 省/州/区域/邻接/补给校验与详情 | GM |
| `orbat.query` | ORBAT 文档/节点查询 | GM |
| `orbat.get` | 读编制树/证据/统计 | GM |
| `info.inbox` | 按地址/类型读 INFO | GM |
| `timeline.node` | 读当前/指定节点摘要 | GM |
| `timeline.diff` | 看两节点差异 | GM |
| `action.preview` | 对任意写意图做 dry-run | GM |
| `action.commit` | 原子提交一项/一批意图 | GM |
| `docs.get` / `docs.write` | 可选文档正文 | GM |
| `export.orbat` | 原生 / mil-org 导出 | GM |
| `export.map` | 原生 polygon/静态状态导出 | GM |

#### Map authoring profile（GM-only，按需加载）

- `map.edit_regions`（州/战略区域/通用 area 的创建/更新/删除/批量）
- `map.edit_provinces`（polygon/显示坐标/地形/类型/所属州）
- `map.split_province` / `map.merge_provinces` / `map.edit_province_shape`
- `map.edit_supply`（hub/rail/network）
- `map.validate` / `map.import` / `map.export_native`

> 尽量用批量 JSON + `dryRun`；不要让模型调用几十次“改一个省”的小工具。

#### ORBAT profile（当前 GM；审批/审计可选）

- `orbat.create` / `orbat.edit`（add/update/move/remove）
- `orbat.plan` / `orbat.commit`（批量预览与原子提交）
- `orbat.import` / `orbat.export`
- `orbat.deploy`（创建/更新运行时 `UnitInstance`，绑定 provinceId）
- `orbat.resources`（装备目录/模板库）

#### INFO profile

- `info.emit`（系统/领域模块或 GM）
- `info.inbox` / `info.get`
- `info.markRead`（可选，属于用户状态，不是世界真值）

#### Timeline profile（GM-only）

- `timeline.tree` / `timeline.node` / `timeline.diff`
- `timeline.fork` / `timeline.step` / `timeline.checkpoint` / `timeline.replay`

### 6.4 写操作的统一信封

```json
{
  "requestId": "req-7f3c",
  "actor": "gm:world",
  "at": "tree1-360",
  "expectedContentHash": "sha256:...",
  "dryRun": false,
  "intent": {
    "kind": "orbat.deploy",
    "orbatDocumentId": "doc:...",
    "nodes": [{"orbatNodeId": "n:...", "provinceId": 123}]
  }
}
```

返回：

```json
{
  "ok": true,
  "node": "tree1-360",
  "changeSetId": "cs:...",
  "warnings": [],
  "artifacts": [{"kind": "map.preview", "uri": "artifact://..."}],
  "approval": {"required": true, "packageId": "dp:..."}
}
```

规则：

- 所有写操作带 `requestId`（幂等键）和 `expectedContentHash`；
- `dryRun=true` 不产生世界写入，但走同一套校验/计划；
- 当前 GM 可直接执行；仍建议保留 preview/审计/回执，后续接 DM 时再复用 `ToolGate.Ask`/`DecisionPackage`；
- 批量操作要么全提交，要么零写入；
- 失败要返回具名错误、具体实体/地址和可操作原因，不要返回空对象或静默部分成功；
- 大图/大树/完整导出文件走 artifact handle，不塞进 MCP result。

### 6.5 权限矩阵（建议）

| 操作 | 当前 GM |
|---|---|
| 读地图/节点/INFO | 全量 |
| 改地图/州/区域/补给 | 允许 |
| 创建/修改 ORBAT 模板 | 允许 |
| 部署单位/改位置 | 允许 |
| 发 INFO | 允许 |
| 推进时间线/分叉 | 允许 |
| 导出 | 允许 |

权限由 `ToolCallAuthorizer` + `ResourceManifest` + `ToolGate` 共同执行；不能只靠工具描述或 system prompt。

---

## 7. 可行性分项判断

| 组合 | 可行性 | 主要工作 | 风险 |
|---|---|---|---|
| **原生 polygon 地图 + Simos 时间线** | 高 | 新地图模块；polygon/邻接/州/区域；ChangeSet；节点地址 | polygon 拓扑/性能；同 tick 语义；历史节点编辑 |
| **地图 + ORBAT** | 高 | `UnitInstance` 桥；`orbatNodeId ↔ unitId`；province 位置/移动/状态；单节点单单位最终态 | 模板与实有混淆；层级位置策略 |
| **ORBAT + INFO** | 高 | 地址规范；受众/归属映射；INFO 只读投影 | INFO 泄漏/伪造；自然语言越界 |
| **INFO + 时间线** | 高 | INFO 作为节点内状态；按节点回放；受众投影 | INFO 条目膨胀；与 Docs 边界 |
| **地图 + INFO** | 高 | 地图实体地址；感知/真值分离 | 把 INFO 误当控制权真值 |
| **AgentLib MCP 工具面** | 高 | WorldTool SPI、ToolRegistry、MCP server、审批、artifact | 工具太多；权限绕过；大结果 |
| **直接改造 SimulatorMosire 为 province** | 低/不建议 | 473 个 Java 文件引用 HexCoord，跨 13 个模块 | 大面积回归；模块边界被破坏；旧经济/军队功能耦合 |
| **直接复制开源地图资产** | 中/需许可审查 | 来源、许可证、版本、资产清单 | 很多社区地图无标准 LICENSE；不能默认“开源” |

### 7.1 关于“把开源 HOI4-like 地图抄过来”

- **技术上可行**：省份规范、州/区域、补给中心/铁路都可以独立实现。
- **法律上必须先审查**：
  - HOI4 vanilla 地图/本地化/州文件是游戏内容，不应作为开放资产；
  - Mappa Mundi/Blank Map/MapGen 等社区资产多数无标准 LICENSE；
  - EarthToHoI4 代码 MIT，但 GADM 等数据有非商业/不再分发限制；
  - `hoi4-agent-tools` 代码 Apache-2.0，但生成物仍可能包含/引用游戏内容。
- **推荐**：抄**结构与算法**，用开放地理数据/合成地图生成自己的数据；把“vanilla 导入”做成需要用户本机游戏安装的 adapter。

### 7.2 关于“不用 HOI4 军事系统”

完全可行，而且与 ORBAT 更匹配。需要先冻结自己的战斗模型边界：

- ORBAT 提供编制/装备/人员；
- 地图提供地形/邻接/补给；
- `CombatModel` 独立计算接触、损耗、撤退；
- INFO 记录感知和战报；
- 时间线记录结果；
- 不要把 HOI4 的 division template 字段硬塞进 ORBAT，否则会同时继承两套格式的债务。

---

## 8. 首版实现范围（当前）

用户已明确：**目前先实现时间线储存和静态状态即可**。

### 8.1 必须实现

1. **严格 NodeAddress**
   - `treeXXX-tickXXX[/<type>[/<id>...]]`；
   - branch/tick/path 严格校验；
   - 语法与 Simos 对齐；
   - 任意路径可解析语法；实体是否存在由 resolver 决定。

2. **静态 polygon 地图状态**
   - `ProvinceShape`：外环 + holes + 多 part；
   - province/state/strategic region/supply hub；
   - `displayPoint`；
   - 省邻接由 polygon 共享边界推导；
   - JTS 校验 polygon 合法性、面积、重叠、显示点、归属关系。

3. **SQLite 时间线存储**
   - nodes 表：`(branch,tick)`、parent、ChangeSet JSON、state hash、savedAt/savedBy；
   - checkpoints 表：全量静态状态；
   - 基线节点保存 checkpoint；
   - 子节点保存相对父节点的 ChangeSet；
   - 查询从最近 checkpoint + delta 回放；
   - 回放后 content hash 自检。

4. **working copy / save / discard**
   - 从已保存节点打开；
   - 多次写入按顺序合并；
   - discard 丢弃内存更改；
   - save 校验后生成已保存节点；
   - 只有 saved 节点能推进或 fork；
   - 历史节点只能 fork，不能原地改写；
   - 不做 undo。

5. **基础 CLI / 测试**
   - 创建小世界基线；
   - 打开 working copy、apply、discard、save；
   - 继续下一节点；
   - 从历史节点 fork；
   - 回放并比较 hash。

### 8.2 本版不做

- 不做 WebUI；
- 不做复杂人工编辑器；
- 不做 ORBAT 运行时单位、INFO UI、战斗模型；
- 不做 DM/决策人权限；
- 不做 HOI4 导出；
- 不做多 Agent MCP 工具面（MCP 在静态状态/时间线稳定后接）。

### 8.3 验收判据

1. `NodeAddress.parse("tree1-360/map/province/berlin")` 成功；空白、空段、非法 tick、`.`/`..` 路径均拒绝；
2. polygon 自交/重叠/显示点在外时校验失败；
3. 每个 land province 恰好一个 state；每个 province 恰好一个 strategic region；
4. 同一 working copy 内多次 apply 合并成一个 ChangeSet；
5. discard 后重新读取的状态 hash 等于打开时保存节点；
6. 未 save 的 working copy 不能推进/开分支；
7. 只有 saved node 可作为 parent；历史节点只能 fork；
8. 节点回放：checkpoint + delta 重建后的 state hash 与保存时一致；
9. fork 分支不受父分支后续节点影响；
10. `mvn test` 全绿；文档检查 `python3 tools/check-docs.py` 全绿。

## 9. 后续未定项

已裁定、不再作为未定项：polygon 权威、同 tick 合并、保存/放弃、fork-only、单单位单节点最终态、INFO 长短文本/自定义类型、当前 GM-only、不支持 HOI4 导出。

仍需后续设计/用户裁定：

1. **渲染方案**：WebGL2/WebGPU + Worker + 二进制 buffer、JavaFX、LWJGL + Dear ImGui、Rust/wgpu 原生、服务端预渲染等；见渲染方案报告。
2. **WSL 部署形态**：WSL 内服务 + Windows 浏览器、WSLg 原生窗口、独立 Windows 原生 viewer。
3. **ORBAT 模板/初始编成/运行时实例**边界。
4. **州/战略区域/通用 Area 是否允许重叠**及各自语义。
5. **静态状态的持久化格式**：SQLite 中 JSON、二进制 geometry、混合。
6. **地图逻辑范围/首版 POC 规模**。
7. **INFO 多包协议**：namespace/type registry/schema version/formatter/导入导出。
8. **GM 审计/回执**：GM 可做任何事，但是否需要不可抵赖日志和保存回执。
9. **战斗模型**：首版只做接触/损耗，还是先只记录战斗结果。

## 10. 参考

- 用户设计原稿：[`2026-11-17-worldsim-architecture-design-source.md`](2026-11-17-worldsim-architecture-design-source.md)
- 邻仓/DSH 侦察：[`../reports/2026-11-17-neighbor-projects-recon.md`](../reports/2026-11-17-neighbor-projects-recon.md)
- SimulatorMosire 文档规则：`~/SimulatorMosire/docs/AGENTS.md`
- SimulatorMosire 时间线设计：`~/SimulatorMosire/docs/superpowers/specs/2026-11-13-timeline-node-editor-and-address-query-design.md`
- SimulatorMosire 节点地址：`~/SimulatorMosire/docs/superpowers/specs/2026-11-13-node-address-and-docs-ownership-design.md`
- SimulatorMosire 地图：`~/SimulatorMosire/simos-map/src/main/java/io/mosire/simos/map/GameMap.java`
- AgentLib `AgentTool`：`~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/tool/AgentTool.java`
- AgentLib MCP server：`~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/mcp/AgentToMcpServer.java`
- AgentLib 决策包契约：`~/ProjectMosire/docs/upstream/agentlib-decision-contract.md`
- Docs 存储契约：`~/ProjectMosire/docs/upstream/docs-storage-contract.md`
- DSH ORBAT 工作台：本会话 `orbat_*` 工具与 `orbat-workbench` skill
- EarthToHoI4：`~/tmp` 侦察副本或 `https://github.com/EdouardSaladier/EarthToHoI4`
- HOI4 地图调研：[`../../hoi4-community-map-resources-survey.md`](../../hoi4-community-map-resources-survey.md)（历史输入）
