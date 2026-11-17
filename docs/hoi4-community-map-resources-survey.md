# HOI4 地图、地块与资源社区生态调研

> **状态**：历史调研输入（2026-11-17）· **取代者**：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md` · **勿据此开工**。  
> 本文保留社区生态/HOI4 数据面调查，作为设计背景；现行架构以用户设计原稿和新专题设计为准。

> 文档状态：调研稿 v0.1  
> 调研日期：2026-11-17  
> 目标版本：Hearts of Iron IV 1.19.4.x（以本机实际安装为准）  
> 范围：HOI4 静态地图/省份/州/战略区域/资源数据，以及社区生成器、Agent/MCP 工具、解析器与可视化编辑器  
> 用途：为“Agent 导向的游戏架构”重写做数据源与技术选型前置调查  
> 说明：本文是工程与生态调研，不构成法律意见。许可证与游戏 EULA 在正式分发前必须单独复核。

---

## 0. 结论先行

1. **不存在一个既完整、又明确开放许可、又跟当前 HOI4 版本同步的“官方地图 + 省份 + 州 + 资源”现成数据集。**  
   最可靠、最完整的主数据源仍是**玩家本机安装的 HOI4 vanilla + DLC + mod 文件**。这些文件适合在本地解析、生成派生索引，但不应直接提交进仓库或随新游戏分发。

2. **社区已经形成三层生态：**
   - **地图内容层**：Blank Map 模板、Mappa Mundi 等大地图 mod，提供现成省份/州/边界内容，但许可证通常不清晰，且与版本强绑定。
   - **地图生成层**：EarthToHoI4、RandomParadox（ArdaGen/MapGen 系）、`hoi4-agent-tools` 的 `create_world` 等，能从真实地理数据或手绘/语义地理模型生成完整 mod。
   - **Agent 工具层**：`hoi4-agent-tools`、HOI4 Live Tools、Agentic-HOI4-Modding、VS Code 扩展等，已经把“读取地图 → 查询实体 → 预览 → 原子修改 → 游戏内验证”做成了 MCP/Agent 工作流。这是本次重写最值得借鉴的部分。

3. **对 Agent 架构而言，最大的问题不是“没有地图”，而是“地图的语义密度和可查询性不够”。**  
   HOI4 的 13k 级省份、1k 级州、约 300 个战略区域如果直接塞进 LLM 上下文，既昂贵又不可推理。必须构建：
   - 静态世界（terrain/geometry/adjacency）与动态世界（owner/controller/units/events）分离；
   - 分层实体（world → theater/front → state group → state → province → pixel）；
   - 图结构（陆地移动图、海运图、铁路补给图、河川/海峡边、空海战略区域图）；
   - 面向 Agent 的查询、聚合、预览、原子提交和回放接口，而不是让 Agent 直接编辑 `.bmp`/`.txt`。

4. **最推荐的技术路线：**
   - **以本机 HOI4 安装为“场景数据源”**，用自研或现成解析器编译成版本化的 `World Snapshot`；
   - **以 `hoi4-agent-tools` 的 catalog/map_inspect/map_rewrite 设计为直接参考**；
   - **以 HOI4 Live Tools 的 mapgraph、计划/执行/校验分层为“Agent 游戏回路”参考**；
   - **以 EarthToHoI4 或 `hoi4-agent-tools` 的 geography model 作为开放/合成地图的补充生成路径**；
   - **不要把 Mappa Mundi、Blank Map、MapGen 模板直接作为核心资产**，除非完成许可证确认和版本适配。

5. **数据缺口主要在“经济与平衡”，不在“几何”。**  
   EarthToHoI4 目前明确不生成工厂和资源；`hoi4-agent-tools` 的参考图生成会给出人口、资源、建筑、归属的“合理假设”，但不是历史事实或平衡结果。  
   对 Agent 游戏来说，资源、人口、工业、补给和军事部署的**可解释、可配置、可验证**，比地图多边形数量更重要。

---

## 1. 先统一术语：HOI4 的“地块”到底是什么

HOI4 的“地图”不是单一网格，而是多层数据：

| 层级 | 实体 | 主要承载 | 典型数据 | Agent 相关性 |
|---|---|---|---|---|
| 像素层 | bitmap pixel | 地图渲染、精确几何 | `provinces.bmp`、`terrain.bmp`、`heightmap.bmp`、`rivers.bmp` | 只在生成/渲染/几何校验时使用 |
| 省份层 | province | 最小游戏地理单位 | terrain、coastal、continent、adjacency、VP、province buildings | 最细粒度移动/战斗/补给节点 |
| 州层 | state | 建设、人口、资源、国家边界 | manpower、resources、state_category、buildings、owner | 经济与政治的核心单元 |
| 战略区域层 | strategic region | 空战、海战、天气、战略投送 | province 列表、weather、naval terrain | 战区/空海权/天气聚合单元 |
| 国家/阵营层 | country/faction | 政治与军事主体 | capital、ideology、faction、army/navy/air | Agent 的行动主体与目标 |

关键区别：

- **省是地图的最小原子单位**，地形、天气影响、VP、陆战、补给和省级建筑发生在省。
- **州是建设、人口、资源和国家边界的核心单位**，资源主要按州统计。
- **战略区域是空战/海战/天气的容器**，其边界通常由多个省组成，陆军移动图不直接以它为单位。
- **所有权与占领/控制不是一回事**：州有 owner，省有实际 controller；战争期间会出现“州归属未变、省内不同省被不同国家控制”的情况。HOI4 的州资源还会按“多数控制者”分配，不能简单把州控制者当成所有省的控制器。
- **视觉地形与游戏地形是两套东西**：`terrain.bmp` 决定地图外观，`definition.csv` 中的 `terrain` 才决定省的实际游戏地形；海省的“海军地形”还定义在战略区域中。

---

## 2. 官方/vanilla 数据面：真正的一手数据在哪里

### 2.1 地图栅格文件

参考：[Map modding](https://hoi4.paradoxwikis.com/Map_modding)

| 文件 | 格式 | 作用 | 注意事项 |
|---|---|---|---|
| `map/provinces.bmp` | 24-bit RGB BMP | 每个像素的颜色对应一个 province ID | 颜色必须唯一；不要抗锯齿；省的像素必须连续；相邻省不能只在四角相接 |
| `map/definition.csv` | 分号分隔 CSV | `ID;R;G;B;type;coastal;terrain;continent` | 省 ID、颜色、类型（land/sea/lake）、沿海、游戏地形、大洲 |
| `map/terrain.bmp` | 8-bit indexed BMP | 视觉地形/材质 | 与 `definition.csv` 的实际地形不是同一概念 |
| `map/heightmap.bmp` | 8-bit grayscale | 海拔/水下高度 | 必须保持调色板；部分编辑器保存会破坏 |
| `map/world_normal.bmp` | 24-bit RGB | 法线贴图 | 常由 heightmap 生成 |
| `map/rivers.bmp` | 8-bit indexed BMP | 河流像素/宽度/流向标记 | 索引值语义固定；不能重排调色板 |
| `map/trees.bmp` | 8-bit indexed BMP | 树木模型分布 | 视觉层 |
| `map/terrain/colormap_*.dds` | DDS | 地表/水体全局着色 | 地图色彩异常常见来源 |
| `map/cities.bmp` + `cities.txt` | indexed bitmap + text | 城市模型分布 | 与 VP 名称不是一回事 |
| `map/buildings.txt` | 文本 | 建筑模型位置和港口对应海省 | 新增省/州后不修正可能崩溃 |
| `map/unitstacks.txt` | 文本 | 单位模型/VP 图标位置 | 视觉/交互位置，逻辑数据另有来源 |
| `map/ambient_object.txt` | 文本 | 世界边缘等环境模型 | 新地图容易遗漏 |

### 2.2 拓扑、移动和特殊连接

| 文件 | 作用 | 关键语义 |
|---|---|---|
| `map/adjacencies.csv` | 特殊省对关系 | 海峡、不可通行、规则连接；`through` 指定中间海省；解析到首个 `-1` 行停止 |
| `map/adjacency_rules.txt` | 海峡/运河通行规则 | 谁能在什么条件下通过 |
| `map/supply_nodes.txt` | 补给枢纽初始位置 | 与铁路网络、首都连通性共同决定补给 |
| `map/railways.txt` | 初始铁路 | 等级 + 省序列；用于战略投送和补给 |
| `map/supplyareas/` | 旧版补给区 | 1.11 之后已被补给节点/铁路体系取代；老 mod 可能仍带 |
| `map/strategicregions/*.txt` | 战略区域 | 省列表、空/海作战区域、天气、海军地形 |

实现时要把“普通邻接”和“特殊邻接”分开：

- **普通陆地邻接**：在 `provinces.bmp` 中按四邻域（上下左右）统计像素接触，接触像素数可以作为边界长度/权重。
- **河川边**：在两个省的共同边界像素对上，若任一侧在 `rivers.bmp` 中是河，则记录 river crossing；`river` 计数可保留原始值，供不同的过河规则使用。
- **海峡/不可通行/规则连接**：来自 `adjacencies.csv`，不能只靠像素邻接推导。
- **海运图**：海省之间的邻接 + 海峡 + 港口/海军基地，不是陆地图的简单子集。
- **铁路/补给图**：铁路省序列 + 补给节点 + 首都，是独立于移动图的有向/无向网络。

### 2.3 州、资源与历史

参考：[State modding](https://hoi4.paradoxwikis.com/State_modding)、[Province](https://hoi4.paradoxwikis.com/Province)

`history/states/*.txt` 的典型结构：

```txt
state = {
    id = 123
    name = STATE_123
    manpower = 500000
    state_category = large_town
    provinces = { 1234 5678 }
    resources = {
        oil = 10
        steel = 20
        aluminium = 5
    }
    local_supplies = 8.3
    impassable = no

    history = {
        owner = GER
        controller = GER
        add_core_of = GER
        buildings = {
            infrastructure = 3
            industrial_complex = 4
            arms_factory = 2
            dockyard = 1
            1234 = { naval_base = 5 }
        }
        victory_points = { 1234 10 }
        1939.1.1 = {
            controller = ITA
        }
    }
}
```

需要注意：

- 州 ID 必须从 1 开始连续；删除州后必须重编号或移动后续 ID，否则可能崩溃。
- 省 ID 也应按连续 ID 处理；缺失 ID 会使定义与颜色/行号错位。
- 一个州只能属于一个战略区域；跨区域会触发地图错误。
- `manpower` 是州总人口/可征兵基础，不是当前可用人力。
- 资源属于州；vanilla 资源键包括 `oil`、`aluminium`、`rubber`、`tungsten`、`steel`、`chromium`，1.17 以后加入 `coal`。必须从 `common/resources/*.txt` 解析，不要硬编码。
- `local_supplies` 是州基础补给，1 单位约等于 0.2 补给。
- `history` 支持带日期的块；Agent 架构需要把“初始值”和“时间线覆盖”分开建模，而不是在导入时压平成单值。
- `victory_points = { province_id value }` 一次只能写一个省；一个州可有多个 VP。
- 首都州中 VP 最高的省通常成为首都省；VP 会反过来影响补给、抵抗和投降逻辑。
- 省级建筑（fort、naval base、railway、supply hub 等）与州级建筑（工厂、基础设施等）必须分开。

### 2.4 战略区域、天气与海军地形

参考：[Strategic region modding](https://hoi4.paradoxwikis.com/Strategic_region_modding)、[Strategic region](https://hoi4.paradoxwikis.com/Strategic_region)

- 战略区域包含省列表，是空战/海战、空优/海权、天气和部分战略效果的作用域。
- 当前 wiki 记录约 **298 个战略区域**（其中区域 15 “Asia” 没有分配省份，因此实际不存在于游戏中）；具体数量应每次从数据中重新统计。
- 一个战略区域内可同时包含陆省和海省；所有海省共享该区域的“海军地形”。
- 天气以 `period` 定义：日期范围、温度范围、天气现象权重、最小积雪等；运行时天气还要按省结算。
- 战略区域边界要与州边界对齐；一个州跨多个区域是地图错误。
- 海洋空间不是只有“海省”：战略区域是海权/空权的聚合层，航线与任务通常按区域而非单省来表达。

### 2.5 规模与版本快照

用到的公开参考：

- 地图尺寸：**5632 × 2048**，即 **11,534,336 像素**；宽高必须是 256 的倍数。参考 [Blank province map](https://hoi4.paradoxwikis.com/File:Blank_province_map.png)、EarthToHoI4、Blank Map 模板。
- 当前社区省 ID 索引：**13,381** 个 province ID。参考 [HOI4 Province IDs](https://hoi4commands.com/province)。  
  这是第三方整理，适合做数量级参考，不应替代本机 `definition.csv` 的权威统计。
- 1.16.3 的 wiki 快照：**13,375 个总省**，其中 10,117 个陆省（有州）、117 个湖省（有州）、3,133 个海省、8 个湖省（无州）。参考 [Province history table 1.16.3](https://hoi4.paradoxwikis.com/User:Mister_Analyst/Province_history_table_HOI4v_1.16.3_Countenance)。
- 1.19.4 州数量：**1,081 个州**。参考 [List of states](https://hoi4.paradoxwikis.com/List_of_states)。
- 1.16.3 快照中 VP 约 **5,781 个**，命名省约 1,461 个；1.19 需要重新抽取，不能直接复用。

> 重要：以上数字会随 DLC/免费更新变化。正式管线应输出 `counts` 和 `source_hashes`，并和本机版本绑定；不要把 13,381/1,081 写死为游戏常量。

---

## 3. 社区资源地图

### 3.1 现成地图/内容资产

| 项目 | 提供什么 | 状态/版本 | 许可 | 适合用途 | 风险 |
|---|---|---|---|---|---|
| **HOI4 本机安装（vanilla + DLC + mod）** | 完整地图、省、州、资源、VP、建筑、铁路、本地化、历史 | 随游戏更新 | Paradox 游戏内容；本地制作 mod 可用，但不可把游戏内容当开源数据分发 | 最高保真场景数据源 | 需要本机安装；版本/DLC/mod 组合会改变数据 |
| **Blank Map for Total Modding** | 5632×2048 空地图模板，默认 46 个省 | 2026-10 仍有更新；Steam Workshop | 未给出明确开源许可 | 快速做全转换地图骨架 | 含游戏衍生资产；版本/无 DLC 测试；不能直接当开放数据 |
| **Mappa Mundi** | 大规模细化地图，约 20,000 个州，多历史时期模板，Toolpack | 社区持续维护的 GitHub 版本；仍可能“buggy mess” | 仓库无 LICENSE；作者 FAQ 称可复用省份但需署名 | 研究高密度州/省划分、历史边界工具 | 许可证不是标准开源；资产/版本兼容性风险高 |
| **HOI4 wiki 的 Blank province/state map** | 5632×2048 的 PNG 视觉参考图，Pyradox 生成，按版本更新 | 1.19.0/1.19.4 附近有更新 | 上传者声明 CC0；但图像仍可能包含游戏地图的衍生表达 | 做视觉对照、演示、测试切片 | 不是游戏可直接读取的 BMP；法律上仍建议复核 |
| **MapGen v2.2 及其地图模板** | 经典 HOI4 地图生成器/模板生态 | 闭源 freeware，版本老旧 | 不明确 | 研究旧工作流、参考输入格式 | 不应作为新架构核心依赖 |
| **HGO / Beautiful States 等地图 overhaul** | 上万州、历史边界/国家内容 | 社区 mod，版本差异大 | 通常无标准代码/数据许可 | 仅作内容研究，需联系作者 | 资产和脚本复用风险高 |

### 3.2 地图生成器

| 项目 | 输出/能力 | 当前状态 | 许可 | 对 Agent 架构的价值 | 关键限制 |
|---|---|---|---|---|---|
| **EarthToHoI4** | 从真实地理数据生成完整 HOI4 mod：国家、省、州、战略区域、天气、地形、河流、heightmap、城市、VP、建筑/单位位置、铁路、补给节点、本地化 | 2026-10 v1.2，Python 3.11+，号称测试到 1.19；数据约 20GB | 代码 MIT；输出受 GADM/HydroSHEDS/NASA/Copernicus/GeoNames 等数据许可约束 | 最适合“真实地球 + 开放地理数据”的合成地图路径；完整导出格式参考 | 明确不生成工厂和资源；GADM 非商业/不可再分发；导出 mod 是混合许可 |
| **RandomParadox / ArdaGen** | 随机世界、完整 HOI4 mod，随机国家/旗帜/国策/单位模板；支持导入地图并生成所有相关图像/文本 | 2026-11 活跃，Windows release；HOI4 支持最完整 | 仓库 GPL-3.0，但依赖 FastWorldGen 未开源，源码不能独立构建 | 研究“完整世界生成 + 内容生成”的边界；可作黑盒参考 | 不可构建/不可完全审计；输出内容随机，不能当历史数据 |
| **`hoi4-agent-tools` `create_world`** | 从参考图或 `geography-model` 生成完整 HOI4 world；含省/州/区域、地形、河流、国家、城市、VP、建筑/铁路/补给 | 2026-11 v4.18.0，活跃 | Apache-2.0（代码）；输出仍受 HOI4/素材权利约束 | **最适合 Agent 的生成接口**：JSON geography model + preview + atomic write + validation | 人口/资源/建筑/归属是生成假设；不能保证历史或平衡；需游戏内验收 |
| **codiren/hoi4-map-generator-fast-mapper** | 从 `input.bmp` 快速生成 HOI4 地图 mod，自动分省/州/区域 | 2025 最后更新，Windows，C++ | 无 LICENSE | 研究快速 raster→province 管线 | 无明确许可；Windows-only；对现代内容覆盖有限 |
| **Provincial (ThomasSlade)** | Python 脚本：填充省颜色、校验 X-crossing/小省、按州图分配省、生成定义/州文件 | 2020 停更 | MIT | 省图/州图分配算法参考 | 旧 Python/依赖；不支持复杂套嵌州；无备份；不可直接用于现代版本 |
| **MapGen v2.2** | 经典地图生成工具 | 老工具 | 不明确 | 历史生态参考 | 闭源、陈旧、与当代 1.19 内容不匹配 |

### 3.3 Agent / MCP 工具（本次最值得关注）

| 项目 | 能力 | 状态 | 许可 | 为什么重要 |
|---|---|---|---|---|
| **hoi4-agent-tools** | MCP server：`map_inspect` 全量 catalog/查询/切片/图层渲染；`map_rewrite` 生成世界与 35 类地图编辑操作；文档/脚本/本地化/错误日志/预览等 | npm 4.18.0，2026-11 非常活跃 | Apache-2.0 | 已把“地图实体目录 + 大结果 artifact + 预览 + 原子写回 + 校验”做成 Agent 接口；是最直接的产品级参考 |
| **HOI4 Live Tools** | MCP server：读取游戏文件、构建 province graph、路线规划、实时点击/时间推进/测试、战略规划与执行/校验 | 2026-11 极活跃；跨平台支持以发布说明为准 | Apache-2.0 | 提供了“Agent 真正玩游戏”的闭环：静态事实 → 计划 → 执行 → 游戏内反馈；`mapgraph.py` 是很好的图模型参考 |
| **Agentic-HOI4-Modding** | 30+ 个 HOI4 modding skills、AGENTS/CLAUDE 模板、离线 wiki、工具编排 | 2026-11 活跃 | Apache-2.0 | 展示了如何把领域知识、工具和 Agent 角色拆成可复用 workflow |
| **HOI4 Modding Tools Extension for VSC** | VS Code 扩展 + 236 个 MCP 工具，78 个地图工具；省/州/区域/补给/铁路/协议校验，支持 dry-run/备份 | 2026-09 更新，README 标 MIT | README 标 MIT，但仓库当前无 LICENSE 文件，复用前需确认 | 说明地图工具可以做到很细；同时是“工具面过宽会压垮 Agent 上下文”的反面案例 |
| **HOI4 Modern Utils** | VS Code 世界地图编辑器：省/州/战略区域/补给区、铁路、补给枢纽、建筑、批量操作、校验、事务式多文件保存 | 0.4.20，2026 仍在更新 | 仓库/市场需单独确认许可 | 适合作为人工 QA/编辑参照；世界地图交互设计成熟 |
| **HOI4 Map Editor: Toolkit** | 独立视觉编辑器：省、州、区域、资源 overlay、政治/地形视图、验证/备份/回滚 | 2026-09 preview | 当前仓库无 LICENSE（删除了 LICENSE 提交） | 可作人工编辑/验证参考，不建议直接复用代码 |

`hoi4-agent-tools` 的关键设计点尤其值得摘录：

- `map_inspect` 默认返回：
  - 全地图 PNG；
  - 可搜索、可点击的 HTML navigator；
  - 一个 JSON catalog，包含每个 active province/state/region 的：
    - 解析后的名称/本地化键；
    - 源文件路径与行号；
    - 栅格 bounds/center；
    - state/region 归属；
    - 邻接、VP、资源、建筑、港口、补给节点、铁路、普通/特殊邻接；
    - 建筑/单位/天气位置；
    - entity locator。
- 大 catalog 和大图不直接塞进 Agent prompt，而是作为 MCP resource artifact 返回；查询支持按 ID、名称、坐标，省几何按 `[y, startX, endXExclusive]` 行段返回。
- `map_rewrite` 支持最多 100 个有序操作，覆盖：
  - world: `create_world`
  - state: `create_state`, `split_state`, `merge_states`, `move_state_provinces`, `update_state(s)`
  - country/territory: `assign_territory`, `repartition_states`
  - province: `create_province`, `split_province`, `merge_provinces`, `remove_province`, `update_province_definition`
  - region: `move_region_provinces`
  - IDs/references: `renumber_map_entity`, `remap_map_references`
  - derived data: `regenerate_map_positions`, `rebuild_supply`
  - adjacency: add/remove normal/special adjacency
  - supply/rail: add/remove supply node/railway
  - positions: building/unit/weather upsert/remove, entity locator
- 它还会在删除州时自动压缩 state ID、修复脚本引用、把建筑位置移动到新州；这些正是 Agent 做地图编辑最容易出错、最需要“事务化”的地方。

### 3.4 解析器 / 数据提取库

| 项目 | 语言/用途 | 许可 | 适用性 |
|---|---|---|---|
| **pyradox** | Python，解析 Paradox `.txt`（HOI4 等），含 worldmap 辅助 | MIT | 适合快速读取 state/strategic region/脚本；比手写正则稳 |
| **jomini** | Rust，低层高性能解析 Paradox save/game files（HOI4 等） | MIT | 适合高性能/大存档/二进制 save；需要处理 HOI4 特有 token/schema |
| **hoi4save** | Rust，HOI4 save 解析/展开 | MIT | 已 archived；可参考，不作为长期依赖 |
| **hoi4.py** | Python，HOI4 save（文本/二进制）解析与绘图 | MIT | 旧但可参考 save 格式；不适合静态地图全量 ETL |
| **hoi4-agent-tools catalog** | 现成的完整地图 catalog（通过 MCP 输出） | Apache-2.0 | 可以先用它验证/对照自研 ETL；但不要把它的输出当作独立开放数据 |
| **HOI4 Live Tools `mapgraph.py`** | Python 单文件 province graph 构建/路线规划 | Apache-2.0 | 很实用的参考实现：BMP 解析、四邻域、河川边、adjacencies、州/区域/补给/铁路、缓存签名 |

---

## 4. 许可证与合规：必须把“代码许可”和“数据许可”分开

这是本调研里最容易踩坑的部分。

### 4.1 HOI4 本体内容

Paradox 的 User Agreement / UGC 条款允许玩家为非商业目的制作和分发 user mods，但核心原则包括：

- 不得把游戏本体和 mod 一起分发；
- 不得让没有 DLC 的用户访问 DLC 内容；
- mod 不得侵犯第三方知识产权；
- 不应把 vanilla 的 `provinces.bmp`、`definition.csv`、本地化、州文件、DLC 资产等直接作为“开放数据”重新分发。

因此建议：

- **仓库只提交解析器、schema、合成 fixture、生成脚本和文档。**
- **构建时要求用户提供自己的 HOI4 安装路径。**
- 如需分发派生数据，先做法律评估；不要因为“这是 ID/事实”就默认可以无条件转载。
- 输出物保留 `source_game_version`、`dlc`、`modset_hash`、`extractor_version` 和许可证清单。

### 4.2 社区生成器的混合许可

- `hoi4-agent-tools` 代码是 Apache-2.0，但它生成的 mod 仍包含/引用 HOI4 格式与可能受版权保护的内容；代码许可不自动覆盖游戏内容。
- `EarthToHoI4` 代码是 MIT，但 README 明确列出：
  - GADM：学术/非商业免费，**不得再分发数据**；
  - HydroSHEDS：CC BY 4.0；
  - NASA Blue Marble：免费使用需署名；
  - Copernicus、GeoNames、Marine Regions、JRC GHS-POP、ERA5/World Bank：各自许可/署名要求；
  - 生成的 mod 是**混合许可作品**，不是纯 MIT。
- `RandomParadox` 代码 GPL-3.0，但依赖的 FastWorldGen 未开源，导致“源代码不能独立构建”；GPL 代码许可也不等于生成内容许可。
- `Mappa Mundi`、Blank Map、codiren fast-mapper、MapGen 等没有标准 LICENSE；作者口头允许/社区惯例不等于可转让的开源许可。

### 4.3 工程规则

建议把许可证作为数据模型的一等字段：

```json
{
  "source_id": "earthtohoi4",
  "source_kind": "generator",
  "code_license": "MIT",
  "data_licenses": ["GADM-NC-NoRedistribution", "CC-BY-4.0", "NASA-Attribution"],
  "output_license": "composite",
  "redistribution_allowed": false,
  "attribution_required": true
}
```

对 Agent 运行时：

- 默认只加载用户本机数据；
- 不把完整 catalog 或 raster 作为公开 API 返回给未授权用户；
- 对外提供的 demo 使用 100% 合成或明确开放许可的世界；
- 生成的截图/预览若包含 vanilla 资产，同样按游戏内容处理。

---

## 5. 关键工程坑与版本陷阱

### 5.1 栅格/地图合法性

- `provinces.bmp` 必须是 **24-bit RGB**，不能压缩，不能有抗锯齿。
- 每种省颜色必须唯一；颜色对应 `definition.csv`。
- 省像素必须连续；不允许四角相接（X-crossing）。
- 省过小（社区工具常引用最小 8 像素）和过大都会引发问题；新增省时应做像素面积/连通性校验。
- 地图宽高必须是 **256 的倍数**；vanilla 为 5632×2048。
- 8-bit indexed bitmap（terrain/rivers/trees/cities）的颜色表**索引本身**参与语义，不能重排。
- `heightmap.bmp` 需要保留灰度/调色板语义，MS Paint/Paint.NET 等保存会破坏。

### 5.2 ID 与引用完整性

- 州 ID 从 1 连续；省 ID 也应按连续处理；战略区域 ID 通常要求连续/稳定。
- 删除州后要处理后续 ID 移动，并重写所有对州 ID 的脚本引用、建筑位置、`STATE_<id>` 本地化。
- 一个省只能属于一个州；一个州只能属于一个战略区域。
- `victory_points` 的省必须属于该州；建筑位置必须引用有效省/海省。
- `adjacencies.csv` 解析到首个 `-1` 行停止；不要假设整文件都能读。
- 港口/海军基地必须绑定有效的相邻海省；否则游戏中可能崩溃。
- 新增省/州后，`buildings.txt`、`unitstacks.txt`、`supply_nodes.txt`、`railways.txt` 不更新可能直接崩溃或出现不可玩状态。

### 5.3 版本/内容漂移

- 1.15 移除了 `map/airports.txt` 和 `map/rocketsites.txt`；旧 mod 仍可能带这些文件，不能按旧 schema 解析。
- 1.17 左右加入 coal；1.19.4 的州表和战略资源需要重新抽取；不要用旧 CSV 硬编码资源集合。
- 2026-10 的能源系统 dev corner/open beta 尚未成为稳定基础；不要把“energy/local power”直接写进 v1 schema，留扩展点。
- 战略区域/州/省的边界和数量会随 DLC/免费更新调整；每次构建必须记录版本并生成 diff。
- `hoi4-agent-tools` 文档记录了一个 1.19.4 的具体崩溃条件：战略区域只剩两个互不相邻的省时，加载可能崩溃；这类“版本特定不变量”应进入 validator。
- 多个 `replace_path` 和 mod load order 会改变最终生效文件；ETL 必须按 HOI4 的覆盖规则合并，而不是简单遍历文件夹。

### 5.4 Agent 特有的坑

- **不要用图片当唯一真相**：LLM 看地图截图可以判断“哪里像前线”，但精确省 ID、边界长度、资源数量必须来自结构化数据。
- **不要把全量数据塞进 prompt**：13k 省、1k 州、298 区域的完整 catalog 远超单次上下文预算。必须分页、聚合、按需渲染。
- **不要给 Agent 直接写文件的能力**：应只允许结构化 intent，由 validator/referee 原子提交；失败要可回滚。
- **不要把“历史事实”与“生成假设”混在一起**：资源、人口、工厂、归属都要带 provenance/confidence。
- **不要忽略控制权**：州 owner 与省 controller 分离；占领、解放、 puppet、核心/宣称是不同的概念。

---

## 6. 对 Agent 导向架构的直接启示

### 6.1 建议的整体架构

```text
┌──────────────────────────────────────────────────────────────┐
│ 数据源层                                                     │
│  - 本机 HOI4 install + DLC + mods（高保真）                  │
│  - EarthToHoI4 / geography-model / 合成世界（开放/可分发）   │
│  - 手工地图模板（仅在有明确许可时）                           │
└──────────────────────────────┬───────────────────────────────┘
                               │ ingest / validate / normalize
┌──────────────────────────────▼───────────────────────────────┐
│ World Snapshot Compiler                                      │
│  - 解析 raster / definition / state / region / history       │
│  - 构建 province/state/region 实体与多层图                    │
│  - 解析本地化、建筑、铁路、补给、VP、特殊邻接                 │
│  - 输出 manifest + entities + graph + geometry + provenance   │
└──────────────────────────────┬───────────────────────────────┘
                               │ immutable static snapshot
┌──────────────────────────────▼───────────────────────────────┐
│ World Runtime                                                │
│  - 静态世界：地形/几何/邻接/资源位点                          │
│  - 动态世界：owner/controller/units/buildings/events          │
│  - 事件溯源日志 + materialized views                          │
│  - 规则引擎 / 确定性 RNG / 时间推进                           │
└──────────────────────────────┬───────────────────────────────┘
                               │ actor-scoped projections
┌──────────────────────────────▼───────────────────────────────┐
│ Agent Gateway (MCP / HTTP / in-process)                       │
│  - 分层查询：world → front → state → province → pixel         │
│  - 地图渲染 / tile / overlay → artifact handle                │
│  - 计划、校验、预览、原子提交、回放                           │
│  - 每个 Agent 只能看到自己的视野/情报投影                     │
└──────────────────────────────┬───────────────────────────────┘
                               │ natural language / tool calls
                         ┌─────▼─────┐
                         │ LLM Agent │
                         └───────────┘
```

### 6.2 四个必须分开的“世界”

1. **静态地理世界**：地形、省界、州界、战略区域、河流、海峡、资源位点、气候。
2. **动态政治世界**：州 owner、省 controller、cores/claims、占领政策、首都、阵营。
3. **动态军事世界**：单位、装备、补给、铁路状态、港口、空/海军任务、战线。
4. **Agent 认知世界**：某 Agent 已知/可见/推断的信息，带不确定性，不等于 ground truth。

如果这四者混在一个大 JSON 里，Agent 会得到大量无关字段，且难以实现 fog-of-war、回放、权限和事务。

### 6.3 推荐的实体与图模型

**核心实体（最小集）**

- `World`：宽高、坐标系、schema 版本、game version、modset、时间轴、source hashes。
- `Province`：类型、地形、大洲、沿海、质心/bbox/像素面积、state/region、几何行段、VP、省级建筑、港口、supply hub、railway level。
- `State`：名称、类别、省列表、人口/manpower、资源、local_supplies、建筑槽、owner、初始 controller、cores/claims、VP 汇总、不可通行。
- `StrategicRegion`：省列表、陆/海构成、naval terrain、weather periods、空/海权重。
- `Country`：tag、首都、颜色、意识形态、阵营、外交状态。
- `Front`（派生）：相邻敌对控制区之间的连续边界段，聚合省、长度、地形、补给、VP 暴露、敌情估计。
- `ResourceDeposit`（建议新增）：resource key、省/州、数量、开采修正、发现状态、来源粒度。
- `Edge`：`a,b`、`kind`、border 像素数、river 计数、strait/through/rule、impassable。
- `Railway` / `SupplyHub` / `BuildingPosition` / `WeatherStation`。

**图分层**

| 图 | 节点 | 边 | 用途 |
|---|---|---|---|
| 陆地移动图 | land province | 四邻域接触 + 河流/海峡/不可通行标记 | 陆军路径、前线、包围 |
| 海运图 | sea province + 港口 | 海省邻接 + 海峡/运河规则 | 海军/运输/登陆 |
| 铁路补给图 | province + hub + capital | 铁路序列、等级、连通性 | 补给/战略投送 |
| 战略区域图 | region/state | 区域邻接、空海通道 | 空优/海权/天气 |
| 政治图 | state/province | owner/controller/claim/core | 战争目标、和谈、占领 |

### 6.4 Agent 工具面：少而深，而不是多而全

现有工具给了一个明确教训：**236 个 MCP 工具并不等于 236 倍能力**，反而会让模型选择困难、schema 占用大量上下文。`hoi4-agent-tools` 默认只暴露一小套核心工具（文档称约 8,000 tokens），其余按 profile 加载，这是更合理的方向。

建议核心工具控制在 10–15 个：

**只读**

1. `world_overview`：日期、阵营、资源/工业/人力概览、主要战线。
2. `map_query`：按名称/ID/坐标查询省/州/区域，分页，返回本地化名。
3. `map_tile`：按 bbox/实体集合返回 PNG + 图层/overlay + 实体元数据 artifact。
4. `entity_get`：省/州/区域/国家详情，限字段选择。
5. `front_list` / `front_get`：聚合后的战线，不返回全部省。
6. `route_plan`：陆/海/铁路路线、成本、风险、过河/海峡。
7. `resource_summary`：资源供需、产地、运输风险。
8. `events_since`：按 revision 拉取增量，不重复全量状态。

**写入/控制**

9. `orders_preview`：接收结构化 intent，返回合法性、成本、预计效果、冲突、影响的省/州清单。
10. `orders_commit`：带 `expected_revision` 的原子提交，返回新 revision、diff、事件。
11. `time_step` / `pause_on_event`：推进固定时间步，事件触发时暂停。
12. `authoring_preview` / `authoring_commit`：地图/剧本编辑，复用 `create_state`、`move_provinces`、`update_resource` 等操作，但不要和 gameplay 权限混在一起。

**关键约束**

- 所有写操作必须带 `expected_world_revision`；
- 失败必须整笔回滚，不能部分写入；
- 大对象用 artifact handle，不塞回 tool result；
- 读工具默认按 actor 做 fog-of-war 投影；
- `orders_preview` 要返回“为什么不能做”，而不仅是 true/false；
- 自然语言命令 → 结构化 intent → 校验 → 提交，四步分离。

### 6.5 观测要做“语义压缩”

建议提供以下派生观测，而不是原始省列表：

- **世界概览**：国家数、州数、资源缺口、工业产能、人力、主要战区。
- **战线对象**：把连续敌我边界聚合成 `front`，包含 `province_ids`、长度、地形分布、河流/海峡、补给状态、VP 纵深、双方兵力估计。
- **战略区域对象**：空优/海权、港口/机场、天气、航道。
- **资源集群**：同类资源产地 + 运输通道 + 风险。
- **行动可达性摘要**：从某省/州出发，N 天可达范围、需要渡河/海峡、补给衰减。
- **差分观测**：自上次 tick 以来控制权变化、建筑完成、资源变化、单位接触。

这样 Agent 看到的是“西线北段补给紧张，敌方在第 3 州有 2 个装甲师，铁路枢纽暴露”，而不是 4,000 个 province 的坐标。

### 6.6 资源与人口建模的建议

HOI4 把资源主要放在州级，这是为它的建设/占领机制服务的。新的 Agent 架构可以保留兼容层，但不必被它限制：

- **兼容层**：`StateResource` 保持州级聚合，能导出/回写 HOI4。
- **游戏层**：可增加 `ResourceDeposit`（省/州级、可发现、可枯竭、可被基础设施影响），让 Agent 的空间决策更有意义。
- **来源标注**：每条资源/人口/工业数据带 `source` 和 `confidence`，区分历史数据、生成假设、平衡调整。
- **能源**：v1 不纳入；作为可选模块，避免把 2026 年 beta 机制固化为核心。
- **人口**：至少区分总人口、征募池、政治/文化分组（如果未来需要）；不要只用一个 `manpower` 整数。

### 6.7 性能与存储建议

在 13k 省、1k 州、约 300 区域的规模下，**不需要图数据库**。建议：

- **元数据/关系**：SQLite（WAL）或 DuckDB/Parquet；
- **邻接图**：CSR/CSC 或压缩邻接表（`graph.bin`/`graph.json.zst`）；
- **几何**：每省 `[y, startX, endXExclusive]` 行段压缩；
- **大对象**：PNG/WebP 预览、Parquet/Arrow catalog，通过 artifact handle 访问；
- **内存**：启动时把省/州/边加载为数组/索引；13k 节点规模完全可控；
- **快照**：`manifest.json` + 内容哈希；静态快照不可变，动态状态用 revision/事件日志增量更新；
- **缓存键**：game 文件路径 + size + mtime + extractor version + modset hash；任一变化就重建。

> `hoi4-agent-tools` 文档给出的一个参考：13.1M 像素地图的边界质量分析约 1.5 秒、额外 100MB 内存；这说明整图分析可做，但不应在每个 Agent 步都全量扫描。

---

## 7. 选型建议

### 7.1 三条可能的路线

| 路线 | 做法 | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| **A. 寄生式 Agent** | 不重写 HOI4，使用 `hoi4-agent-tools` + Live Tools + skills，让 Agent 通过 MCP 操作现有游戏 | 最快验证 Agent 玩法；直接获得完整 HOI4 规则/内容 | 受游戏 UI/性能/平台限制；无法真正重写核心循环；依赖外部工具 | 先验证需求、收集行为数据 |
| **B. 新引擎 + 编译 HOI4 场景** | 自研 simulation，导入本机 HOI4 的静态世界并转换为内部 schema；HOI4 只作为场景源/兼容导出 | 可控、可测试、Agent-first；可保留 HOI4 地图质感 | 需要自研规则/经济/军事；导入器复杂；许可证要求自带游戏 | **推荐作为重写主线** |
| **C. 社区内容全量复用** | 直接拿 Mappa Mundi/Blank Map/MapGen 资产作为新游戏世界 | 内容快、地图细 | 许可不清、版本漂移、质量参差、难以维护 | 仅原型/研究，不建议生产 |

### 7.2 建议采用 B，A 作为验证夹具

具体建议：

1. **先做 A 的最小闭环**（1–2 周）：
   - 安装/配置 `hoi4-agent-tools` 与 HOI4 Live Tools；
   - 在本机 HOI4 上跑一次 `map_inspect` catalog，核对省/州/区域数量；
   - 让 Agent 完成一个“查询 → 计划 → 预览 → 提交 → 游戏内验证”的简单任务。
   - 产物：一份真实 catalog、一次地图渲染、一次失败回滚记录。

2. **并行做 B 的 World Snapshot POC**（2–4 周）：
   - 读取本机 install，输出 `manifest + provinces + states + regions + edges + resources + geometry runs`；
   - 与 `hoi4-agent-tools` 的 catalog 对照计数/字段；
   - 用合成 fixture 写单元测试（不依赖游戏资产）；
   - 建立 validator 和 quality report；
   - 实现 5 个只读 Agent 工具（overview/query/tile/state/route）。

3. **再做 Agent Runtime 闭环**：
   - 事件日志 + revision；
   - `orders_preview/commit`；
   - 时间推进；
   - 战线聚合；
   - fog-of-war 投影；
   - 回放/重放测试。

### 7.3 不应做的事

- 不要把 vanilla 的 `provinces.bmp`/`definition.csv`/州文件提交到公开仓库；
- 不要把 `hoi4-agent-tools` 的 catalog 直接当“开放数据”发布；
- 不要在没有明确许可时把 Mappa Mundi/Blank Map/MapGen 资产打进产品；
- 不要让 LLM 直接改 BMP/CSV/脚本；
- 不要用单个巨大的 JSON 表达世界；
- 不要在没有 validator 的情况下相信生成器输出。

---

## 8. 还需要确认的问题

以下问题建议在进入实现前由产品/技术共同确认：

1. **目标形态**：是“给 HOI4 加一个 Agent 外挂”，还是“脱离 HOI4 的新游戏引擎”？两者对数据保真和许可证的要求完全不同。
2. **地图来源**：真实地球（EarthToHoI4）还是原创世界（geography model/手绘）？是否需要历史边界？
3. **规模**：13k 省原版规模，还是像 Mappa Mundi 的 20k 州？后者对 Agent 上下文和模拟性能要求高一个量级。
4. **经济粒度**：资源只到州，还是需要省/资源点级？人口是否要分组？
5. **多 Agent**：单国 Agent、多个国家 Agent，还是“裁判 + 玩家 Agent”？是否需要 fog-of-war 与私有状态？
6. **时间粒度**：小时/日/周 tick？Agent 行动是否需要暂停？
7. **兼容性**：是否需要把新引擎状态导出回 HOI4，或至少导入 HOI4 mod 做对照？
8. **许可证策略**：是否接受“用户自带 HOI4”作为前提？是否有商业分发计划？

---

## 9. 参考来源（访问日期均为 2026-11-17）

### 官方/社区 wiki

- [Map modding](https://hoi4.paradoxwikis.com/Map_modding) — 地图文件、BMP 格式、省/州/区域、adjacency、supply、buildings
- [Map](https://hoi4.paradoxwikis.com/Map) — 省/州/战略区域层级、地图模式、空海权
- [Province](https://hoi4.paradoxwikis.com/Province) — 省、VP、地形、天气、建筑、核弹
- [State modding](https://hoi4.paradoxwikis.com/State_modding) — 州 schema、资源、manpower、history、building、state ID 连续性
- [Strategic region modding](https://hoi4.paradoxwikis.com/Strategic_region_modding) — 区域 schema、weather
- [Strategic region](https://hoi4.paradoxwikis.com/Strategic_region) — 298 区域、海军地形、区域列表
- [List of states](https://hoi4.paradoxwikis.com/List_of_states) — 1.19.4 共 1,081 州
- [Province history table 1.16.3](https://hoi4.paradoxwikis.com/User:Mister_Analyst/Province_history_table_HOI4v_1.16.3_Countenance) — 13,375 省及陆/海/湖拆分
- [Blank province map](https://hoi4.paradoxwikis.com/File:Blank_province_map.png) — 5632×2048、CC0 声明的视觉地图
- [Blank state map](https://hoi4.paradoxwikis.com/File:Blank_state_map.png) — 州边界视觉地图

### 数据/工具索引

- [HOI4 Province IDs](https://hoi4commands.com/province) — 13,381 省 ID 的第三方索引
- [hoi4-agent-tools (GitHub)](https://github.com/klimPaskov/hoi4-agent-tools) / [npm](https://www.npmjs.com/package/hoi4-agent-tools) — MCP 地图/脚本工具
- [hoi4-agent-tools map docs](https://github.com/klimPaskov/hoi4-agent-tools/blob/HEAD/docs/map.md) — map_inspect/map_rewrite/create_world、catalog、geography model
- [hoi4-agent-tools tools](https://github.com/klimPaskov/hoi4-agent-tools/blob/HEAD/docs/tools.md) — MCP 工具面
- [HOI4 Live Tools](https://github.com/klimPaskov/HOI4-Live-Tools) — 实时游戏 MCP、mapgraph、计划/执行/校验
- [Agentic-HOI4-Modding](https://github.com/klimPaskov/Agentic-HOI4-Modding) — Agent skills/workflow
- [EarthToHoI4](https://github.com/EdouardSaladier/EarthToHoI4) — 真实地理数据生成完整 HOI4 map mod
- [RandomParadox](https://github.com/panik4/RandomParadox) — 随机 Paradox 世界生成
- [Mappa Mundi (N0zza)](https://github.com/N0zza/MappaMundi) — 大规模地图/州内容
- [Mappa Mundi Skymods](https://hearts-of-iron-4.smods.ru/archives/23232) — 功能说明/FAQ
- [Blank Map for Total Modding](https://hearts-of-iron-4.smods.ru/archives/15802) — 空地图模板
- [HOI4 Modding Tools Extension for VSC](https://github.com/OlanderIAO/HOI4-Modding-Tools-Extension-for-VSC) — 236 MCP 工具/78 地图工具
- [HOI4 Modern Utils](https://marketplace.visualstudio.com/items?itemName=TheCascadian.hoi4modernutils) — VS Code 世界地图编辑器
- [HOI4 Map Editor (AdriianCOE)](https://github.com/AdriianCOE/HOI4-Map-Editor) — 独立视觉编辑器
- [HOI4 Province Editor (ScottyThePilot)](https://github.com/ScottyThePilot/hoi4_province_editor) — 被 archive 的 MIT 省编辑器
- [Provincial](https://github.com/ThomasSlade/Provincial) — MIT Python 省/州图工具
- [pyradox](https://github.com/ajul/pyradox) — MIT Paradox `.txt` 解析器
- [jomini](https://github.com/rakaly/jomini) — MIT/PDS parser
- [hoi4.py](https://github.com/samirelanduk/hoi4.py) — MIT HOI4 save parser

### 许可/条款

- [Paradox User Agreement](https://legal.paradoxplaza.com/eula?locale=en) — 以当前官方版本为准
- [Paradox UGC/Mod Policy](https://www.paradoxplaza.com/mod-policy-en) — user mods 非商业、不得捆绑游戏内容

### 检索陷阱

- Hugging Face 上的 `Livioni/hoi4d` 是 **HOI4D：4D egocentric human-object interaction** 数据集，与 Hearts of Iron IV **无关**；不要因为名字相同而误用。
