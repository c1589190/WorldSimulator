# 事件时间线、地图条件、外交与省级统计调查

> **状态**：调研输入
> **日期**：2026-11-20
> **本文性质**：调查证据与工程建议
> **源码基线**：`faac654`
> **实施进展**：[事件时间线](../specs/2026-11-20-event-timeline.md) · [行军与交战的地图条件](../specs/2026-11-20-land-edge-conditions.md)
> **范围更新**：§4 和 §6 的法定归属与首版经济建议以[用户原稿 §22](../specs/2026-11-17-worldsim-architecture-design-source.md#22-军事经济范围与库存口径)为准。

用户询问能否让每个发生变化的时刻成为节点，并要求先调查军队读地图、外交以及省级政治经济和占领统计。下文的实现方案待评审。已有编制、控制权、GM 裁决和地址约定见现行契约。[剧本时钟](../specs/2026-11-20-simulation-clock-and-marches.md) · [战斗与行军](../specs/2026-11-20-battle-and-march-conditions.md) · [GM 与决策人](../specs/2026-11-19-1936-europe-agent-turns.md)

## 1. 让变化发生的时刻成为节点

### 当前行为

`TimelineNode` 有 `(branch, tick)`、父节点、增量、状态哈希和现实保存时间。调用方在 `edit_save` 提供大于父节点的 tick。一份草稿可先后执行多个命令，保存时只发布最终节点。剧本时间由状态、增量或旧剧本日历给出，查看器按父链排列这些保存节点。由此，同一剧本时刻可以有多个保存节点，一次推进中的抵达也可能落在两个保存节点之间。[节点](../../../src/main/java/io/mosire/worldsim/timeline/TimelineNode.java#L17) · [保存](../../../src/main/java/io/mosire/worldsim/timeline/TimelineService.java#L353) · [推进](../../../src/main/java/io/mosire/worldsim/state/WorldChangeSet.java#L77) · [时间查询](../../../src/main/java/io/mosire/worldsim/app/WorldApplicationService.java#L591)

在德波演练存档副本通过 MCP 读取 `march-demo` 父链与 `march-demo-3` 的分段用时。两段抵达时刻为 **1936-01-01 05:08:45 和 11:59:11**，下一批可选节点分别为 **06:00 和 12:00**。战斗分支的 `battle-demo-7` 与 `battle-demo-8` 同为 1936-01-01 12:00。证据见本文末尾的复查方法。

### 建议语义

- **每个发生可回放状态变化的剧本时刻形成一个节点。** 同一分支同一时刻的抵达、条约生效、控制变化或周期结算进入同一批 `ChangeSet`，节点表示这一时刻结算后的世界状态。不同时间以事件时间推进。行军途中等连续变化可以从已提交路线计算。GM 可以显式保存一个回合截止点，供决策人共享下回合基点。
- **分支仍是历史分叉。** 每个新节点显式带剧本时刻和精度，回放得到的状态时钟必须与该时刻一致。原有 `(branch, tick)` 仍作稳定寻址，新推进可由程序分配序号，`savedAt` 继续用于审计。新时间索引按分支父链重建。旧节点按现存日期或未知值显示，旧地址、哈希和父链照旧可查。同日的旧补录可折叠展开，从旧时刻重结算走分叉。
- **一次 GM 推进可以预览多个节点。** 调度器按时间取到期事件。同一时刻先处理生效或到期的授权，再根据共同基点校验抵达等依赖项，由 GM 裁决同一实体上的冲突。需要 GM 输入时停在待裁决时刻之前，显示原因。确认后一次发布通过验证的节点链及请求回执，失败重试按基点哈希和请求 ID 复用回执。每个时刻一份增量并沿用检查点。回放读取已保存的结算结果及规则版本。
- **导航以时间为主。** 可按日期、事件类别或实体地址定位，并展开每个时刻的事件。跨度长时按日或周折叠，按可见窗口分页。地图几何、地形层和铁路静态索引按各自内容版本复用。外交条款和人口变更可以更新侧栏而无需重绘区域着色。[现有回放缓存](../../../src/main/java/io/mosire/worldsim/timeline/TimelineService.java#L143) · [查看器缓存版本](../../../src/main/java/io/mosire/worldsim/app/viewer/WorldViewerServer.java#L189)

参考 SimPy 的离散事件调度与确定性同刻顺序。本项目的同刻顺序应由规则和 GM 裁决确定。邻仓 SimulatorMosire 按日拆分推进并逐日保存，检验大步推进与拆段推进的终态一致性。WorldSimulator 可按事件时刻分段，以相同基点、事件输入和回合截止点，比较两种推进方式的节点时间和状态哈希。邻仓证据为 `~/SimulatorMosire/simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java:101`。[SimPy 调度说明](https://simpy.readthedocs.io/en/latest/topical_guides/time_and_scheduling.html)

## 2. 地形、跨河、铁路和补给

| 条件 | 现有实现 | 建议新增 |
| --- | --- | --- |
| 地形与行军 | `world_movement_estimate` 需要从部署省出发的普通陆省链。逐段读取目标省地形和通行，按实编最低速度、配准距离与剧本系数估耗时。 [行军估算](../../../src/main/java/io/mosire/worldsim/app/WorldMovement.java#L107) | 支持给目的省预览一组游戏内可达路段。每段同时返回出发省、进入省、经过的邻接及依据。 |
| 跨河 | `geo-reference.json` 中的河流为折线和相对宽度，查看器绘制使用。普通陆地邻接记录省 ID、类型和共享边长度。 [河流渲染](../../../src/main/java/io/mosire/worldsim/app/viewer/ViewerMap.java#L99) · [邻接结构](../../../src/main/java/io/mosire/worldsim/map/Adjacency.java#L6) | 把过河事实挂在邻接边及具体渡点上。按河线与共享边的位置关系提出候选，区分沿省内流经、沿界、相交和误差内接近。证据不足返回待核或未知，已确认的游戏通道可带桥渡资料。 |
| 铁路 | 已有线路、等级和有序省序列。补给搜索按铁路的相邻段建图，行军工具未返回当前段的铁路。 [铁路模型](../../../src/main/java/io/mosire/worldsim/map/Railway.java#L7) · [补给建图](../../../src/main/java/io/mosire/worldsim/app/LandMarchRules.java#L57) | 从同一铁路的连续省对查实际线路及等级。分别显示轨道存在、运转状态、使用许可和可用输送能力。铁路输送作为显式的游戏内运输方式。 |
| 补给 | `world_supply_status` 和逐段条件返回首都、铁路、补给中心及一跳内覆盖的连通状态。判定在本方有效管辖内，缺控制时使用归属作为通行依据。 [连通与速度](../../../src/main/java/io/mosire/worldsim/app/LandMarchRules.java#L32) | 首先给出连通证据、来源中心、受阻原因和资料缺口。随后以显式库存、运力和消耗记录结算期内可分配量与缺口。跨国补给由专门许可控制。 |
| 交战条件 | `resolveBattle` 检查交战省和参加者部署是否合规，保存 GM 输入的结果、损耗及可选控制变更。 [裁决](../../../src/main/java/io/mosire/worldsim/app/WorldBattle.java#L31) | 共用上述读接口，分别显示双方当时的地形与补给，以及每条游戏内接触边的河流、铁路信息。裁决可引用这份条件和依据，结果由 GM 填入。 |

用固定 Magna 图源的 `rivers.bmp`、`provinces.bmp` 和 `definition.csv` 做了像素抽检。两个位图同为 4096×3072。河流像素按脚本使用的索引 0–11 取值。枚举同一对四连通陆地像素，两侧都是河流像素时得到 **7,579** 个不同陆省对，任一侧有河流像素时得到 **12,823** 个。结果是两种判法的候选规模，证明边界判读对取样规则敏感。首版应优先验证少量合成边界与已校对的游戏地图样本，保留候选证据。 [底图接入说明](../specs/2026-11-19-readonly-map-viewer.md#河流接入方案) · [固定图源](https://github.com/AzurCrystal/Magna-Europa/tree/d7a57759c188a034c49a0e96aceb1702fa76fc74/map)

地图域拥有地形、流线、邻接和铁路。作为规则输入的资料按地图几何和数据哈希固定版本，修正过河关系走正常变更工具。军事域消费同节点的通行、补给和地图事实。新路线随规则版本记录采纳的依据与预计抵达时刻。控制或许可在途中改变时，在变化节点复核未执行路段，重估结果写入新节点。旧行军保留已保存耗时与规则的回放解释。

钢4 的铁路、枢纽、轨道恢复与补给地图模式可作为交互参考。这里更值得优先呈现每段路线的证据、许可和缺失信息，供 GM 与 Agent 追溯。同样可以借鉴占领区专门视图，森林、城市、河流等底图信息仍由共同地图层提供。[Paradox 补给开发日志](https://store.steampowered.com/news/posts/?appids=394360&enddate=1626890445&feed=steam_community_announcements) · [Paradox 占领与抵抗系统说明](https://www.paradoxinteractive.com/media/press-releases/paradox-interactive/secrets-and-sabotage-take-center-stage-in-la-resistance)

## 3. 外交条款和显示

国家档案目前能查 ID、名称、别名、颜色、首都、来源及控属省数量。现有外交相关状态是定向军事通行权。`setTransitRight` 的授予方和接受方有明确方向，它可作新条款读视图的兼容输入。[国家数据](../../../src/main/java/io/mosire/worldsim/country/CountryState.java#L14) · [权限判断](../../../src/main/java/io/mosire/worldsim/country/CountryState.java#L85)

建议以有稳定 ID 的协定及条款为权威。一个协定保存参与方、签署日、生效与失效的时刻或日期精度、状态、来源和文本引用。条款保存类型、方向、适用省份或资源、有效期和生效条件。战争、停战等显示共同状态。通行、保证、禁运、贸易与补给使用权各有自己的方向和适用范围。谈判和批准用独立事件记录，条款只有在 GM 裁决生效后才供行军、补给或经济域读取。首版可支持战争状态、互不侵犯、停战、定向通行和贸易许可。涉及第三方的协定可共享一条记录。旧通行权查询应兼容派生读视图，避免双写事实。

SimulatorMosire 提供有向关系和按参与方查询的外交事件。WorldSimulator 可沿用参与方和事件历史的思路，并给通行、禁运等条款加上可由规则检查的字段。邻仓证据为 `~/SimulatorMosire/simos-gov/src/main/java/io/mosire/simos/gov/nation/DiplomaticRelation.java:6` 与同目录 `DiplomaticEvent.java:7`。

以德波 1934 年声明为开局样例。它在 **1934-01-26 签署、1934-02-24 生效**，约定以和平方法解决争端并保留双方对第三方既有义务。在剧本中可登记有证据的双边互不侵犯条款。军事通行和补给权限各由独立条款授予。另以但泽自由市校验地位与权限，条约规定国联保护的自由市可同时被纳入波兰关税边界，经济管辖与地方治理可以不同。[波兰法令公报第 124 号](https://eli.gov.pl/eli/DU/1934/124/ogl) · [换文公报第 125 号](https://eli.gov.pl/eli/DU/1934/125/ogl) · [声明档案原件转录](https://nuremberg.law.harvard.edu/documents/2410-german-polish-agreement-to-assure?mode=text) · [《凡尔赛条约》第 102–104 条](https://history.state.gov/historicaldocuments/frus1919Parisv13/ch12subch11)

查看器选中国家后，可从双边侧栏查看当前状态、适用条款、下一次到期时间、变更时间和证据。外交地图以选中的一个国家为参照，可切换战争、协定或定向通行的图层。MCP 返回同一节点下的国家档案、条款和事件地址。后续决策人仅访问 GM 放行的条款与简报。草案、秘密条款和公开政策各有自己的可见范围。[既定 Agent 回合](../specs/2026-11-19-1936-europe-agent-turns.md#4-一个推演回合)

## 4. 省级政治经济与占领统计

当前地图省包含地形、几何及所属州和战略区，国家域逐省保存归属方与控制方。现有存档没有省级人口、产业和治理账，国家域也缺少成体系的政治状态。[省模型](../../../src/main/java/io/mosire/worldsim/map/Province.java#L12) · [领土模型](../../../src/main/java/io/mosire/worldsim/country/CountryState.java#L37) · [当前状态结构](../../../src/main/java/io/mosire/worldsim/state/StaticWorldState.java#L17)

| 领域 | 首批记录 | 汇总规则 |
| --- | --- | --- |
| 人口 | 统计地域 ID、普查日、人数、常住或当日在场口径、证据。游戏省级估算额外保存分配依据和覆盖。 | 从同口径最低可用层汇总。保留未知和未分配量，州、国家与占领范围统计从相同基表生成。 |
| 经济 | 有来源的设施或地区账，保存产业、位置、产能、产量、库存、单位、统计期、产权及可调拨方。首版可从少量关键品类开始。 | 库存在同一时刻加总，产出按不重叠期间加总，产能与实际产量分别显示。可调拨量来自结算后的有效许可、当地供给和运输记录。 |
| 政治 | 国家有政体、政府职位或任期、动员和政策。行政区或省作为政策覆盖范围，记录管理机关、适用政策、执行起点和证据。 | 汇总各辖区的政策适用人口、行政能力或其他有定义的指标。范围交叠时明确生效层级。比例带分母，口径或分母缺失时保留未知。 |
| 占领 | 逐省保存法定归属、实际控制和具有生效时间、类别及证据的行政安排。军队到达、交战、确立控制及建立行政分别有事件。 | 按节点列出已确认的占领范围、辖区人口、当地生产和可用份额。省份统计用实际控制和相应行政记录。部分被控的州按其成员省计数和汇总。 |

例如演练州有甲乙两省，甲、乙产能分别为 6 和 4 个剧本单位，另一方仅控制乙。州地理产能仍为 10，另一方控制地区的产能为 4，可调拨数取决于生产、行政安排和可用运输。占领开始时间也决定哪个统计期间使用哪些权利。1907 年海牙陆战法规第 42 条以实际建立的敌军权力说明占领，给出了区分驻地、控制和占领类别的历史语义依据。[1907 年条约附则](https://avalon.law.yale.edu/20th_century/hague04.asp)

**人口分配先保全史料总量。** 历史统计区和游戏省的边界各自保留。按与统计表同年的历史区划配准。通过地名、城镇或行政区边界和现有 `world_geo_lookup` 匹配，先安置能核对的地点值，再把其余部分按有据的聚落或居住面积权重分配给相交省。整数分配和未分配余额相加须等于来源总额。原册值和省级派生值不能重复加总。缺少匹配区划或可信权重时直接显示原统计区总额及覆盖，省级留空。WorldPop 的重新分区方法提供技术参考，分配权重需匹配剧本年代。[IPUMS 历史区划](https://mosaic.ipums.org/historical-gis-datafiles) · [WorldPop 方法](https://www.worldpop.org/methods/populations/)

**让变化进入同一条时间线。** 人口迁移、产区停产、条款生效和管理方移交各自有明确的生效时间。通过 `Command → ChangeSet → TimelineNode` 保存这些变化，用有来源的开局基线和带版本的游戏参数结算。控制改变前后的产量按各自期间归账，人口与设施保持地理位置直至具体变更发生。查看器可切换归属、实际控制和行政的着色口径，单省面板显示人口、经济和政策，州与国别面板显示分口径合计、覆盖和证据。

## 5. 1936 史料检索与复查

| 用途 | 本轮查到的资料与检索办法 | 入库时的核对 |
| --- | --- | --- |
| 外交日期与条款 | 检索 `Deklaracja 26 stycznia 1934 124 125 wymiana dokumentów ratyfikacyjnych`，交叉读取波兰 ELI 的签字、换文和生效信息，以及纽伦堡证据 TC-21 的正文转录。 [法令公报](https://eli.gov.pl/eli/DU/1934/124/ogl) · [TC-21](https://nuremberg.law.harvard.edu/documents/2410-german-polish-agreement-to-assure?mode=text) | 对照宣告、批准、换文和生效。阅读条款后再录程序可识别的权利。日期精度按来源保存。 |
| 国家与行政制度 | 检索 `Ustawa Konstytucyjna 23 kwietnia 1935 227`，查第 2、3 条的职位与机构、第 81 条及公报的生效日期。 [波兰 1935 年宪法正文](https://eli.gov.pl/api/acts/DU/1935/227/text.html) · [公报元数据](https://eli.gov.pl/eli/DU/1935/227/ogl) | 宪法用来录组织和职权。任期、实际人选与执行范围查相应任命及行政文件。 |
| 波兰人口和区划 | 检索 `Drugi Powszechny Spis Ludności 9.XII.1931 Województwo Warszawskie`。确认华沙市与华沙省有分册。统计总局的利沃夫分册开头还列出同一普查使用 1932 年区划整理的县表，说明观察日期和区划日期都需跟踪。 [华沙市分册](https://mbc.cyfrowemazowsze.pl/dlibra/publication/16923/edition/14171) · [华沙省分册](https://mbc.cyfrowemazowsze.pl/publication/16925) · [统计总局利沃夫分册及表目](https://statlibr.stat.gov.pl/exlibris/aleph/a22_1/apache_media/UNR2S2X2METXQDKMQB63LIASN2GTPB.pdf) | 用统计原表口径找同版区划，先选单一区域做守恒核验。1931 表直接保存其普查日期。推估 1936 年数另记模型和来源。 |
| 工业与交通 | 检索 `Mały Rocznik Statystyczny 1936 Główny Urząd Statystyczny` 和 `514401303_1936 Statistisches Jahrbuch Eisenbahnen`，找到波兰年鉴目录和德国年鉴原页。德册第 193 页的货运表列 1934、1935 年，有吨和吨公里等不同单位。 [波兰年鉴书目](https://www.cyfrowemazowsze.pl/dlibra/publication/17001/edition/14489) · [德国年鉴交通原页](https://digi.bib.uni-mannheim.de/fileadmin/statjahrb/514401303_1936/pdf/514401303_0246.pdf) | 保存表页、单位、时段和空间范围。经济原册的全国或地区流量可作数量标尺。铁路具体路段和场站需另查同期线路图或运营记录。 |

**本地验证。** 在仓库根运行 `python3 -B runtime/scenario-1936/domain-recon/read-mcp.py`，rc=0。复制现有演练存档到临时目录，用 `target/worldsim-replay.jar mcp` 查询 `timeline_lineage`、`world_marches`、`world_movement_estimate`、`world_supply_status` 和 `world_lookup`。从出发时刻累加保存的 `leg.seconds` 并与父链比对。估算样本有平原、城市和补给连通字段，现有条件结构缺跨河与当前路段铁路字段。原世界清单 SHA-256 前后一致。详情在 `runtime/scenario-1936/domain-recon/mcp-read.json`。记录里的运动和战斗均来自演练分支。

运行 `python3 -B runtime/scenario-1936/domain-recon/river-edges.py`，rc=0。以固定源提交和调色板读取两张位图，选陆地像素并按四连通省对去重。输出保存于同目录 `river-edges.json`。该检查验证图幅与候选规模。跨河分类准确度、历史桥渡、区划分配与新时间语义的实现验收为 `NOT_RUN`。

## 6. 建议实施顺序

1. **先定节点语义。** 用演练副本验证一次推进产生多个抵达节点，同刻变化入同一节点，到期的授权按生效时间复核。验收旧地址、分叉、重启重放、划分不同推进区间得同一状态、预览与保存重试，以及多节点的查看器切换。
2. **接好地图条件查询。** 用普通陆省、沿河边界、图源缺口和有铁路但停用的游戏样本做区分。行军和 GM 交战预览共享这一套事实，按节点返回证据。
3. **建立一个小的德波与但泽闭环。** 录有原文的开局条款、一个历史统计区的人口与基础产出，以及按省治理与控制的统计。验收一次条款变更影响许可、一次演练控制改变影响归账。所有变更通过 MCP 草稿预览和保存。
4. **再扩欧洲与 Agent 回合。** 沿用已记录的证据和导入流程。不同决策人从同一节点读取 GM 授权的档案并提交决策。GM 保存按时刻分段的裁决，回到地图检验条款、行军、经济与控制变化。 [现有欧洲剧本计划](../plans/2026-11-20-1936-europe-workflow.md)
