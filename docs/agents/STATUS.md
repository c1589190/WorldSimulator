# STATUS.md —— 当前状态

> **更新时间**：2026-11-20 02:05 CST
> **当前目标**：接入外部地理参考并通过程序工具建立可玩的 1936 欧洲剧本，再推进 Agent 回合。
> **当前阶段**：战斗兵牌与时间线切换优化已部署。8767 运行历史预览，8768 运行包含新旧分支的独立演练世界。[战斗兵牌](../superpowers/specs/2026-11-19-readonly-map-viewer.md#战斗兵牌) · [欧洲开局与推演计划](../superpowers/plans/2026-11-20-1936-europe-workflow.md)
> **责任区与设计**：[地图单位与 ORBAT 契约](../superpowers/specs/2026-11-19-unit-address-and-orbat-format.md)、[实施计划](../superpowers/plans/2026-11-19-country-orbat-pilot.md)、[地理定位与国家调查](../superpowers/reports/2026-11-19-geography-country-orbat-recon.md)。

## 源码基线与有效入口

- 仓库 `~/WorldSimulator`，单模块 Maven 工程，JDK 21。交付分支 `main` 跟踪 `origin/main`。当前提交与同步状态见 `git log -1 --oneline` 和 `git status --short --branch`。
- 交付范围为文件存档、迁移、GM MCP 及查看器的代码、测试与文档。打包产物是 `target/worldsim-0.1.0-SNAPSHOT.jar`。`world/` 是 gitignored 本地用户数据，旧 `runtime/worlds/magna-europa.db` 保留。
- 用户设计原稿：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`；专题设计：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`；裁定索引：`docs/superpowers/DESIGN_RULINGS.md`。
- 项目维护入口：`docs/AGENTS.md`、`docs/agents/MAINTENANCE_RULINGS.md`；构建和启动步骤见根 `README.md`。
- 早期问题审计见 `docs/superpowers/reports/2026-11-17-phase1-gap-audit.md`；其中两项缺口和 MCP 状态是历史快照，已在报告头标明本轮取代。
- 邻仓参考：`~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；`~/ProjectMosire` 当前工作树；DSH `orbat_*` 上一轮只读侦察为 sequence 15、5 份编制文档。

## 已实现与使用边界

- 无参数启动 jar 时，从**工作目录**的 `world/world.json` 加载世界并以文件锁保证同一世界只有一个进程；没有存档时报错，不会自动新建。入口：`src/main/java/io/mosire/worldsim/app/WorldRuntime.java:22`、`src/main/java/io/mosire/worldsim/world/WorldDirectory.java`。
- `world.json` 的已提交分支入口为权威；不可变 `node.json`/`changes.json` 和按内容哈希共享的 JSON checkpoint 对象支持回放，保存回执与提交入口原子发布。重启忽略暂存和不可达节点；已提交内容的外部篡改触发完整性错误。入口：`src/main/java/io/mosire/worldsim/world/FileTimelineStore.java:32`、`src/main/java/io/mosire/worldsim/world/SnapshotFiles.java`。
- 创世 checkpoint 是权威恢复基线，不可删除；其他被清单引用的 checkpoint 缺失也会报错，目前没有自动重建命令。只有 `view/` 和未来可选的索引可作为可删除重建的派生产物。
- 世界视图 `world/view/` 是带节点及状态 hash 的可读分块，可重建；导出包含可达已提交历史、数据及可选来源报告，不包含缓存、源文件或未提交内容。入口：`src/main/java/io/mosire/worldsim/world/WorldView.java:17`、`src/main/java/io/mosire/worldsim/world/WorldExport.java:10`。
- 正常启动、保存和回放不需要 SQLite，当前没有 SQLite 索引；SQLite 驱动只供 `world/imports/` 中的旧库显式只读迁移，源库不删除。入口：`src/main/java/io/mosire/worldsim/world/WorldMigration.java:24`。
- AgentLib GM stdio MCP 已提供摘要、实体查询、验证、树/差异、打开草稿、批量预览/应用、保存/放弃、视图及导出；工具角色与世界资源权限由服务端实施。草稿在内存，保存要求基点 hash、版本和 requestId；重启后同请求可重放保存回执。入口：`src/main/java/io/mosire/worldsim/app/WorldTools.java`、`src/main/java/io/mosire/worldsim/app/WorldApplicationService.java:32`。
- 世界可带独立的 `geo-reference.json`。`world_geo_lookup` 和查看器支持已校对别名、WGS84 坐标查省与省址反查，原生省形用于候选判断。查看器可显示带来源和日期的史料边界。试点需固定地图和史料输入，可用准备脚本和 GM MCP 导入器复跑。[复现记录](../superpowers/reports/2026-11-19-1936-source-research.md#复现导入)
- `world_territory_gaps` 与「归属调试」用同一节点的归属查找待补陆省。参考带史料范围时按预期省和国家筛选；工具可用 `scope=all` 查看全图。网页的高亮和清单可用于旧节点对照。[调试契约](../superpowers/specs/2026-11-19-1936-europe-agent-turns.md#7-边界复核归属调试与师级序列)
- 保存前检查普通邻接是否对应 polygon，并禁止引入新的不一致；特殊邻接单独处理。`verifyAll` 重放每个节点自身 delta 并校验 checkpoint，修复旧审计所列两项缺口。入口：`src/main/java/io/mosire/worldsim/map/PolygonTopology.java:53`、`src/main/java/io/mosire/worldsim/timeline/TimelineService.java:214`。
- `world/provenance.json` 将来源提示与节点/hash 关联，记录来源、推断和几何修复；后代若更改对应字段，查询不再沿用该字段的旧提示。其是附加证据而非第二套状态。入口：`src/main/java/io/mosire/worldsim/app/WorldProvenance.java`。
- 只读地图查看器可用 `viewer [--port 8765]` 独立启动，或用 `mcp --viewer-port 8765` 与 GM MCP 共用进程。点选地块同时描绘本格、所属州和战略区，可用 `state:<id>`、`region:<id>` 定位。配准文件提供河流时，随底图和瓦片默认绘制。[查看器专题](../superpowers/specs/2026-11-19-readonly-map-viewer.md)
- 国家模块保存国家及每省的归属与控制证据。单位模块保存原生 ORBAT 文档、理想版本引用、实际编制和部署，支持 DSH `mil-org` 交换。地名、国家和单位共用 `NodeAddress`，变更仍走 `Command → ChangeSet → TimelineNode`。主要接口为 `src/main/java/io/mosire/worldsim/app/WorldTools.java`。
- `world_movement_estimate` 返回实编或理想编制中的最低行军速度和瓶颈节点。路线由同节点的相邻陆省组成，公里数用现有配准估算。编制速度可经 `putOrbatDocument` 录入。[行军耗时契约](../superpowers/specs/2026-11-20-unit-movement-duration.md)
- GM 通过草稿命令 `setSimulationTime`、`startMarch`、`advanceTime` 和 `cancelMarch` 管理剧本时间及行军。抵达每段后更新部署，`timeline_lineage` 与 `world_marches` 查询已保存结果，浏览器线性时间线可回看对应路线。[时间与行军契约](../superpowers/specs/2026-11-20-simulation-clock-and-marches.md)
- `resolveBattle` 保存单场 GM 裁决，写回实际编制的人员和装备损耗，并可更新交战省控制。战斗记录经 `world_battles`、统一地址及地图标记查询。`setTransitRight` 管理定向通行。`world_supply_status` 和逐段行军估算返回地形、铁路补给连通及通行依据。[战斗与行军契约](../superpowers/specs/2026-11-20-battle-and-march-conditions.md)
- 兵牌放大时在实际部署点附近展开，全图时聚合计数，低于全图 0.9 倍时隐藏。左侧军队面板提供单位名单和编制图入口。独立窗口用卡片和连线展示编制，支持实际与理想切换、折叠、缩放和节点详情。编制图入口见 `src/main/resources/io/mosire/worldsim/viewer/military.js:418`。
- 战斗裁决节点的交战省显示虚拟兵牌。它可随部队叠放、独立展开及点选，叠牌分别统计部队和战斗。相同地图的交互回放复用已校验地图以及全图和瓦片。[查看器专题](../superpowers/specs/2026-11-19-readonly-map-viewer.md)
- INFO 任意地址、长短文本及自定义类型的运行时、DM 权限、离线视图更改导入保留为后续工作。

## 当前本地世界与来源

- 社区地图是欧洲范围的 **Magna Europa: Reforged**，源 `https://github.com/AzurCrystal/Magna-Europa.git`，固定提交 `d7a57759c188a034c49a0e96aceb1702fa76fc74`，HOI4 1.19.* / mod 0.99.6；raw 在 `runtime/imports/MagnaEuropa`。上游根目录无标准 LICENSE，公开分发前仍需确认；下载脚本 `tools/fetch-community-map.sh`。
- 当前世界为 `world/world.json` 的 `magnaeuropa`，只有 `main-0`，hash `sha256:097b73086f835cd63df788a787c09cd32fd165be896b39b158c85c43139371ff`。21670 省、2986 州、253 战略区域、674 补给中心、1921 铁路、65196 邻接、27504 EntityLinks。
- 1936 德波当前世界位于 `runtime/scenario-1936/opening/demo/world/`，8767 运行 `target/worldsim-replay.jar`。其地理参考含 3,873 条河道折线。`de-pl-1936-2` 记录德国 1,428 省、波兰 907 省、但泽自由市 5 省，德国 29 个师、波兰 31 个师及 213 个下属编制节点。两次导入的日期参考均为 1936-01-01。[Windows 查看器](http://127.0.0.1:8767/?node=de-pl-1936-2)
- 当前演练世界位于 `runtime/scenario-1936/battle/demo/world/`，8768 运行相同 jar。从独立的 `march-demo-6` 分叉保存 `battle-demo-7` 至 `battle-demo-10`，有标记为演练的通行变化、战斗和实编损耗。[Windows 战斗演练](http://127.0.0.1:8768/?node=battle-demo-10) · [旧行军分支](http://127.0.0.1:8768/?node=march-demo-4)
- 1936 葡萄牙验收世界位于 `runtime/portugal-1936-demo.ncxp4mk5/world/`。`portugal-1936-1` 记录 79 省归属和 Lisboa 的单位 `por-governo`。`portugal-1936-2` 在此基础上补入剧本中的 7 条部署，合计 Lisboa 3 个、Porto 3 个、Coimbra 2 个。实际控制与编制数量保持未知。原 8765 服务仍指向原 `main-0`。[部署来源](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/history/units/POR_1936.txt#L40)
- 演示世界的 `counter-test-1` 从 `main-0` 分叉。Lisboa（19738）有 6 个明确标注的测试单位，显示坐标同为 `(783, 2274)`，来源标记为 `fixture:viewer-counter-zoom`。构造命令见演示目录的 `counter-zoom-test-commands.json`。
- 演示世界的 `division-test-1` 从 `main-0` 分叉，两个测试装甲师均部署在 Lisboa（19738）的同一显示点。两师各有独立实际编制树，共用一个理想模板。地图只投影两条师级部署实例，团、营、连、排由编制树展示，见 `src/main/java/io/mosire/worldsim/app/WorldQueries.java:139`。测试来源为 `fixture:viewer-division-hierarchy`，构造命令见演示目录的 `division-qa/commands.json`。
- 只读一致性备份在 `world/imports/legacy.db`；它用于迁移追溯，正常运行不读取。`world/view/current.json` 指向 `main-0` 的可读视图；独立导出位于 `world/exports/bb64c7db-1d4c-4cd2-a408-798552c4a172/`。
- 通过固定源重新导入并复现完全相同的创世 hash，定位 heightmap 推断海省 `1644, 3721, 8372` 和 `GeometryFixer` 修复省形 `3458, 9587, 16241, 16505, 16622, 16923, 17515, 17967`。信息记录在 `world/provenance.json`。2026-11-19 逐项核对原始位图与导入结果，并在查看器验证各省的来源提示。证据见下表。
- 真实地图普通邻接 64387 条，与 polygon 推导的 64387 条一致，特殊邻接 809 条。早期探针只排除 `special-` 前缀，误将 10 条 `kind=special` 计作普通邻接；原始记录核对后已纠正，实际普通拓扑差异为 0。迁移报告 `world/migration-report.json`、证据 `/tmp/worldsim-border-probe.log`。

## 最近验证

### 2026-11-20 战斗兵牌和时间线切换

证据位于 `runtime/scenario-1936/replay/`。在同一 21,670 省演练世界对比 `battle-demo-7` 至 `battle-demo-10` 的往返切换。旧进程采样落在 `WorldMapValidator.validateGeometry` 的省形交叠检查。首次加载演练分支后，新版可跨节点复用地图校验结果、回放状态和已解码底图。

| 项目 | 工具或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify`，提交前从当前源码复跑 `mvn -o verify` | **PASS**，rc=0，102 tests / 0 failures / 0 errors / 0 skipped。覆盖快照跨分支复用、全量验证发现已缓存地图的磁盘对象损坏、战斗牌的节点归属及存档分支的地图版本。见 `verify-build.log` 和 `verify-publish.log`。 |
| 节点接口 | `bench-api.py 8768`（旧版）和 `bench-api.py 8769`（新版） | **PASS**，相同节点 hash。旧版缓存未命中时读取节点为 7,998–8,564 毫秒，生成底图为 375–397 毫秒。新版首次打开演练分支后切节点为 4–7 毫秒。见 `before-api.log` 和 `after-api.log`。 |
| 浏览器切换 | `bench-browser.cjs 8768`，分别运行旧版和新版 | **PASS**，旧版缓存未命中时 8,295–8,776 毫秒，新版同一 8 步为 44–96 毫秒且未请求新全图或瓦片。见 `before-browser.log`、`after-browser-live.log`。 |
| 实际地图浏览器 | `verify-browser.cjs 8768` | **PASS**，全图混合叠牌分开计数，放大可直接点战斗牌，低倍率或关闭兵牌时隐藏。旧节点切换保留放大时的底图与瓦片，控制改变后底图更新。见 `verify-browser-live.log`、`battle-stack.png` 和 `battle-expanded.png`。 |
| 从磁盘验证历史 | 在演练副本运行 `java -jar target/worldsim-replay.jar verify` | **PASS**，rc=0，读取并验证全部 11 个节点，用时 104.25 秒。见 `verify-world.json` 和 `verify-world-time.log`。 |
| 部署和 Windows | `deploy-live.py`、Windows `curl.exe -4 --noproxy '*'`、世界清单比较 | **PASS**，8767 和 8768 均可访问。演练 11 个节点的状态 hash 和日期均匹配，三个世界的清单逐字节一致。进程启动至接口验收约 8.5–8.9 秒。见 `deploy-live.log` 和 `windows-*.json`。 |

### 2026-11-20 单场战斗、通行和行军条件

证据位于 `runtime/scenario-1936/battle/`。

| 项目 | 工具或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify` | **PASS**，rc=0，101 tests / 0 failures / 0 errors / 0 skipped。覆盖超扣、重复裁决、未知基数、实有与理想编制、定向通行、撤权、补给和地形。见 `verify-build.log`。 |
| 真实 MCP | `python3 -B runtime/scenario-1936/battle/verify-real.py` | **PASS**，rc=0。反向授权被拒，正向授权后写入断供与城市系数，撤权后部队停在 775。裁决把人员 100 扣到 91，装备 12 扣到 9，守方未知基数未填数。裁决后 11774 的通行和补给连通恢复。重启读回一致，`timeline_verify` 通过 11 节点。见 `verify-real.json`。 |
| 浏览器 | `verify-browser.cjs 8768` | **PASS**，交战标记、点选裁决、单位、损耗、通行受阻、速度提示以及旧 `march-demo` 分支均通过。见 `verify-browser.log`、`battle-result.png` 和 `blocked-march.png`。 |
| Windows 与清单 | `curl.exe -4 --noproxy '*'`、主世界和历史 1936 清单校验 | **PASS**，两端口可读。8768 返回演练裁决，8767 的历史节点仍为 60 个单位。见 `windows-battle.json` 和 `windows-history.json`。 |

### 2026-11-20 剧本时间、实际行军与回放

证据位于 `runtime/scenario-1936/marches/`。

| 项目 | 工具或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify` | **PASS**，rc=0，99 tests / 0 failures / 0 errors / 0 skipped。覆盖多段、取消分支、试算不写入、幂等重试、旧日期和快照回放。见 `verify-build.log`。 |
| 真实 MCP | `python3 -B runtime/scenario-1936/marches/verify-real.py` | **PASS**，rc=0。00:00 出发，03:00 在首段途中，06:00 已抵达 11774 并走第二段，12:00 已抵达普鲁什库夫 775。取消预览与正式保存隔离。重启后四个阶段一致，`timeline_verify` 通过 7 个节点。见 `verify-real.json`。 |
| 浏览器与路径 | 8767、8768 的 `verify-browser.cjs` | **PASS**，日期、线性节点、前后跳转、分支和祖先链接、窄屏、逐段位置、已走和待走的像素通过。见 `verify-browser*.log`、`en-route.png` 和 `arrived.png`。 |
| 地图、归属与编制回归 | `verify-terrain.cjs 8767`、`verify-gaps.cjs 8767` | **PASS**，华沙组合点选和区域 key 定位通过。旧节点 192 项，修正节点 0 项，定位、分页和 Kielce 编制图通过。 |
| Windows 连通和清单 | `curl.exe -4 --noproxy '*'`、主世界和历史 1936 清单校验 | **PASS**，8767 返回两个旧节点的开局日期，8768 返回已抵达记录与省地址。见 `windows-timeline.json` 和 `windows-arrived.json`。 |

### 2026-11-20 陆军行军速度与耗时

- 隔离构建 `mvn -o clean verify` **PASS**，rc=0，97 tests / 0 failures / 0 errors / 0 skipped。覆盖子树、并列瓶颈、实际与理想、已知 0 与未知、邻接、配准和回放。证据为 `runtime/scenario-1936/movement/verify-build.log`，产物为 `target/worldsim-movement.jar`。
- 用 `python3 -B runtime/scenario-1936/movement/verify-mcp.py` 在导出副本做真实 GM stdio 验证，**PASS**，rc=0。华沙 11800 到 11774 约 18.01 公里。用标记为演练的编制数据得最低 3.5 公里每小时和约 309 分钟。旧节点缺速度，跳省路线被拒绝。重启后结果一致，`timeline_verify` 通过 4 个节点。证据为同目录 `verify-mcp.json`。主世界及 8767 世界的清单校验一致。

### 2026-11-20 新工作流盘点

- `curl -fsS 'http://127.0.0.1:8767/api/nodes?limit=5'` 返回 3 个节点的父链、hash 和现实保存时间 `savedAt`，rc=0。两份德波导入清单的剧本日期均为 1936-01-01。查看器当前节点选项以地址作显示名。
- 本机 DSH `0.2.2-rc.3`，`dsh headless --help` rc=0，支持任务、工作区权限预设和会话运行。仓库 Skill 加载与各国任务的副本联通 `NOT_RUN`。功能现状、代码位置和实施验收见[欧洲开局与推演计划](../superpowers/plans/2026-11-20-1936-europe-workflow.md)。

### 2026-11-19 组合点选与默认河流

证据位于 `runtime/scenario-1936/terrain/`。[图源与可复现流程](../superpowers/specs/2026-11-19-readonly-map-viewer.md#河流接入方案)

| 项目 | 工具或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify` | **PASS**，95 tests / 0 failures / 0 errors / 0 skipped。覆盖同一请求的三级选中结果、合并形状有洞，以及默认底图和瓦片的河道。见 `verify-build.log`。 |
| 点选、查找和旧节点 | `node runtime/scenario-1936/terrain/verify-browser.cjs 8767` | **PASS**，点华沙得到 `province:11800`、`state:508` 和 `region:44`。验证同州与异州成员、key 或地址定位、切换着色与节点、旧链接进入地形。华沙首次点选约 57 毫秒。见 `verify-browser-live.log` 和 `8767-three-selections.png`。 |
| 默认河流和来源 | `python3 runtime/scenario-1936/terrain/verify-rivers.py 8767` | **PASS**，135,108 条像素邻接在折线中各出现一次。莱茵河、奥得河、维斯瓦河及多瑙河的四处校验点在全图和瓦片有蓝色像素。创世节点同样默认绘制河流。见 `verify-rivers-live.log`。 |
| 地理参考与剧本复现 | `tools/prepare-1936-pilot.py --continue-from`、`tools/river_reference.py` | **PASS**，参考与正式端口使用的输入一致，剧本准备仍生成同样的 244 条 GM 命令。 |
| 归属与编制回归 | `node runtime/scenario-1936/terrain/verify-gaps.cjs 8768` | **PASS**，同范围旧节点 192 项，修正节点 0 项。分页和 Kielce 编制图通过，见 `verify-gaps.log`。 |
| Windows 与世界完整性 | `curl.exe -4 --noproxy '*'`、PNG 像素比较、清单 hash | **PASS**，8767 的组合点选响应和底图可从 Windows 获取，与 WSL 返回的地图像素一致。主世界与当前 1936 世界各自的清单通过阶段前后校验。见 `windows-pick.json` 和 `windows-terrain.png`。 |

### 2026-11-19 州与战略区域点选

证据位于 `runtime/scenario-1936/areas/`。河流数据预检和接入做法见[查看器专题](../superpowers/specs/2026-11-19-readonly-map-viewer.md#河流接入方案)。

| 项目 | 工具或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify` | **PASS**，94 tests / 0 failures / 0 errors / 0 skipped。额外断言覆盖合并省界、保留洞、图层点选和独立 key。见 `verify-build.log`。 |
| Magna 查看器 | `node runtime/scenario-1936/areas/verify-browser.cjs 8768` | **PASS**，点不同成员得到同一 `state:508`，切换另一个州和 Masovia `region:44`，按 key 查询并回到省份点选。首次合并加出图约 3.3、4.2 秒，缓存后重试切换约 61、53 毫秒。见 `verify-browser.log`、`verify-browser-warm.log` 和截图。 |
| 归属调试回归 | `node runtime/scenario-1936/areas/verify-gaps.cjs 8768` | **PASS**，旧节点 192 个待补省，修正节点为 0。列表、翻页和编制图均通过。见 `verify-gaps.log`。 |
| 演示和 Windows | 8767 Chromium 回归及 `curl.exe -4 --noproxy '*'` 请求两种 `/api/pick` | **PASS**，华沙点位分别命中 3 省的 `state:508` 和 116 省的 `region:44`。阶段前后主世界清单 hash 一致。见 `verify-browser-live.log`、`windows-state.json`、`windows-region.json`。 |

### 2026-11-19 德波边界、师级序列与归属调试

证据位于 `runtime/scenario-1936/opening/`。[查证路径与复现](../superpowers/reports/2026-11-19-1936-source-research.md#德波开局复核与补录)

| 项目 | 工具或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify` | **PASS**，rc=0，94 tests / 0 failures / 0 errors / 0 skipped，含查缺项、全图范围和保存后旧节点对照。见 `verify-build.log`。 |
| 导入与回放 | `submit-scenario.py --apply`，重启 `verify-mcp.py` | **PASS**，rc=0，244 条命令三批预览，回放 3 个节点。旧节点 192 个缺项，新节点在同一史料范围内为 0。核对全部 60 个师部和 213 个编制子节点。见 `import-receipt.json`、`verify-mcp.json`。 |
| 普通陆地邻接 | 对比两节点 `world_view` 的省归属和邻接 | **PASS**，rc=0，同一德波边界 113 条邻接全部有归属，旧节点有 41 条。见 `border-edges.json`。 |
| 截图与界面 | Chromium 同倍率对照地图，切换调试节点并点击缺项 | **PASS**，rc=0，旧节点有粉色缺项，修正节点无同色高亮。分页、定位、Kielce 编制图均通过。见 `gaps-browser.log` 和前后截图。 |
| Windows 地址 | `curl.exe -4 --noproxy '*'` 请求 8767 的新节点和 `/api/territory/gaps` | **PASS**，rc=0，返回 3 个国家、60 个师部和史料范围内 0 个缺项。见 `viewer-windows.json`、`gaps-windows.json`。 |

### 2026-11-19 1936 德波地理与导入

证据位于 `runtime/scenario-1936/`。外部输入版本、地名预检、史料页码和省级复核步骤见[史料查证记录](../superpowers/reports/2026-11-19-1936-source-research.md)。

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录 `mvn -o clean verify` | **PASS**，rc=0，94 tests / 0 failures / 0 errors / 0 skipped，含地名、坐标边界、反查与基准 hash 回归。见 `verify-build.log`。 |
| 配准与复现 | `tools/prepare-1936-pilot.py` 与两次生成结果对比 | **PASS**，rc=0，42 个拟合点、15 个留出点、12 个同省像素命中，最大残差 4.749 地图单位。见 `prepared/research.json` 和 `prepare-confirmed.log`。 |
| 批量导入和保存 | `tools/submit-scenario.py --apply`（其余参数见复现记录） | **PASS**，rc=0，450 条命令五批预览并保存一次，五批无已有归属冲突。核对两国归属数和四处部署，原世界清单校验一致。第二份独立导入的预览、应用和保存 hash 一致。见 `import-receipt.json`、`import-parity.json`。 |
| 无提交预览 | 用同一导入器预览 3 条命令，省略 `--apply` | **PASS**，rc=0，预览后丢弃草稿，副本清单与创世导出逐字节相同。见 `dry-run/receipt.json`。 |
| 重启 MCP | `python3 -B runtime/scenario-1936/verify-mcp.py` | **PASS**，rc=0。确定命中、多候选、覆盖外与反查均通过。4 个师部及其编制可回读，`timeline_verify` 验证 2 个节点，导出保留地理参考。见 `verify-mcp.json`。 |
| 查看器 | `node runtime/scenario-1936/verify-viewer.cjs` | **PASS**，rc=0。Chromium 验证归属底色、史料边界、别名、地理候选和反查、左侧军队面板、编制图及切回旧节点。见 `verify-viewer.log`。 |
| Windows 地址 | `curl.exe -4 --noproxy '*'` 请求 8767 `/api/map?node=de-pl-1936-1` | **PASS**，rc=0，返回正确分支 hash、2 个国家、4 个部署。见 `viewer-windows.json`。 |

### 2026-11-19 编制图窗口

证据位于 `runtime/portugal-1936-demo.ncxp4mk5/formation-window-qa/`。

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录运行 `mvn -o clean verify` | **PASS**，rc=0，93 tests / 0 failures / 0 errors / 0 skipped。见 `maven.log`。 |
| 浏览器交互 | 在 8766 运行 `node browser.cjs` | **PASS**，rc=0。Chromium 核对两师各自的连线、同级卡片位置、排级详情、分支折叠、拖动及缩放、编制切换、关窗后保留地图选中、编制节点地址直开、空编制、节点切换和 390 px 触屏。见 `browser.log` 和截图 `first-division.png`、`second-division.png`、`mobile.png`。 |
| Windows 访问 | Windows `curl.exe` 请求 8766 的页面和 `/api/map?node=division-test-1` | **PASS**，rc=0，页面包含编制图窗口，返回原样例 hash 和 2 个部署单位。见 `windows-index.html`、`windows-map.json`。 |

### 2026-11-19 同级装甲师与下属编制

证据位于 `runtime/portugal-1936-demo.ncxp4mk5/division-qa/`。

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录运行 `mvn -o clean verify` | **PASS**，rc=0，93 tests / 0 failures / 0 errors / 0 skipped。自动回归覆盖同省两师投影、同点及独立编制树。见 `maven.log`。 |
| 部署保存及回放 | 通过 `seed.py` 调用 GM MCP，在演示目录运行新 jar 的 `verify` | **PASS**，rc=0，校验 5 个节点。核对两师同点、同层级、独立实际编制和共用理想地址。主世界清单逐字节一致。见 `seed.log`、`verify.json`。 |
| 浏览器交互 | 运行 `node browser.cjs` | **PASS**，rc=0。两张 50×32 师级兵牌点选、下属团到排展开、共用理想模板、叠牌数字 2、最低倍率隐藏与恢复通过。390 px 触屏点选通过。见 `browser.log`、`stack.png`、`first-division.png`、`second-division.png`。 |
| Windows 访问 | Windows `curl.exe` 请求 8766 的 `/api/map?node=division-test-1` | **PASS**，rc=0，返回两个部署单位。见 `windows-map.json`。 |

### 2026-11-19 兵牌尺寸与最低倍率

证据位于 `runtime/portugal-1936-demo.ncxp4mk5/counter-zoom-qa/`。

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量构建 | 隔离目录运行 `mvn -o clean verify` | **PASS**，rc=0，92 tests / 0 failures / 0 errors / 0 skipped。日志 `maven.log`。 |
| 节点回放 | 在演示目录运行新 jar 的 `verify` | **PASS**，rc=0，校验 4 个节点。见 `verify.json`。 |
| 浏览器 | 在 8766 运行 `node browser.cjs` | **PASS**，rc=0。Chromium 实测 60×38 兵牌，6 个单位滚轮缩小后依次聚合和隐藏，反向恢复。隐藏后点部署点命中 Lisboa。390 px 触屏及侧栏改变视口后保持最低倍率通过。见 `browser.log` 和三档截图。 |
| Windows 访问 | Windows `curl.exe` 请求 8766 的 `/api/map?node=counter-test-1` | **PASS**，rc=0。返回 6 个部署单位，见 `windows-map.json`。 |

### 2026-11-19 兵牌与编制侧栏

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量测试与打包 | 同源隔离构建运行 `mvn -o clean verify` | **PASS**，rc=0，92 tests / 0 failures / 0 errors / 0 skipped。增加兵牌投影与浏览器模块路由断言。日志 `/tmp/worldsim-military-verify.log`，演示 jar `target/worldsim-country-orbat.jar`。 |
| 剧本部署和全链回放 | `python3 /tmp/worldsim-seed-portugal-counters.py`，演示目录运行 jar 的 `verify` | **PASS**，rc=0，验证 3 个节点。新增 `portugal-1936-2`，主世界清单逐字节一致。证据 `/tmp/worldsim-portugal-counters-seed.log`、`/tmp/worldsim-portugal-counters-verify.json`。 |
| 浏览器交互 | `node /tmp/worldsim-portugal-counters-browser.cjs` | **PASS**，rc=0。Chromium 验证全图叠牌、同省展开、缩小再聚合、左侧编制与右侧省份并行、实际与理想切换、地址、来源和节点切换。浏览器注入的测试编制验证排级和 0 人，390 px 触屏验收及 300 单位密集排布保留全部名单。截图 `/tmp/worldsim-counters-unit.png`、`/tmp/worldsim-counters-mobile.png`，日志 `/tmp/worldsim-portugal-counters-browser.log`。 |
| Windows 访问 | `curl.exe -4 --noproxy '*'` 请求 8766 的 `/api/map?node=portugal-1936-2` | **PASS**，rc=0，返回 8 个部署单位与新节点 hash。证据 `/tmp/worldsim-portugal-counters-windows.json`。 |
| 文档与资源检查 | `python3 tools/check-docs.py`、`git diff --check`、`node --check` 两个 JS 模块 | **PASS**，rc=0，生产 jar 内的 JS、CSS 与工作树一致。 |

### 2026-11-19 国家与单位切片

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量测试与打包 | 同源隔离构建运行 `mvn -o verify` | **PASS**，rc=0，92 tests / 0 failures / 0 errors / 0 skipped。包含旧 canonical JSON、草稿原子性、理想版本绑定、单位独立实际编制、差额和历史部署回归。日志 `/tmp/worldsim-country-orbat-verify.log`，演示 jar `target/worldsim-country-orbat.jar`。 |
| 真图 MCP 与回放 | `python3 /tmp/worldsim-seed-portugal-pilot.py`，在副本中用新 jar 运行 `verify` | **PASS**，rc=0。通过名称找到 Lisboa，79 省批量预览并保存分支，读出单位与两份编制地址和兼容导出损失报告。两个节点校验通过，原始 `main-0` hash 保持一致。证据 `/tmp/worldsim-portugal-pilot.log`、`/tmp/worldsim-portugal-pilot-verify.json`。 |
| 浏览器与 Windows 访问 | `node /tmp/worldsim-portugal-browser.cjs`，从 Windows 请求 8766 的 `/api/map` | **PASS**，rc=0。Chromium 验收归属图层、地名命中、省份与单位跳转、两份编制、单位标记点选及回到 `main-0`。Windows 侧成功返回样例节点。日志 `/tmp/worldsim-portugal-browser.log`，截图 `/tmp/worldsim-portugal-1936-viewer.png`。 |
| 文档与资源检查 | `python3 tools/check-docs.py`、`git diff --check`、`node --check src/main/resources/io/mosire/worldsim/viewer/viewer.js` | **PASS**，rc=0，11 个必需文件、4 篇 spec、7 篇报告。 |

### 2026-11-19 地理与国家调查

真实 Magna 的 Lisboa、Paris 等省份通过地址与逻辑坐标命中。按 1936 剧本 `POR` 州史提取 19 州、79 省，副本 MCP 的 79 个标签批量预览通过。正式国家命令不在当前 schema 中。25 个主图城市的 Miller 留一验证同省命中 20 个，北美插图需独立配准，州 947 的名称存在上游证据冲突。两项探针退出码均为 0，命令、范围和证据见[调查](../superpowers/reports/2026-11-19-geography-country-orbat-recon.md)。

### 2026-11-19 查看器交付

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 提交前全量复跑 | `mvn -o test` | **PASS**，rc=0，90 tests / 0 failures / 0 errors / 0 skipped。日志 `/tmp/worldsim-save-progress-test-2026-11-19.log`。 |
| 全量测试与打包 | `mvn -o clean verify` | **PASS**，rc=0，90 tests / 0 failures / 0 errors / 0 skipped。查看器测试覆盖洞、共享边界、瓦片、草稿隔离、节点/hash 校验。日志 `/tmp/worldsim-viewer-verify.log`。 |
| 真实地图 Chromium 验收 | 用已打包 jar 启动 `viewer --port 0`，由 Chromium 操作真实 `main-0` | **PASS**，rc=0。21,670 省全图、平移缩放、512 像素瓦片、点选、按 ID/地址定位、三种着色、1,921 条铁路、674 个补给中心和 390px 窗口布局通过。本机底图 1,142,291 字节。日志 `/tmp/worldsim-viewer-browser-2026-11-19.log`，截图 `/tmp/worldsim-viewer-qa-2026-11-19/magna-overview.png` 与 `magna-province.png`。 |
| 同进程 MCP 与历史节点 | 在 Magna 导出副本运行 `mcp --viewer-port 0`，通过 MCP 改名、分支保存，再用 Chromium 切换节点 | **PASS**，rc=0。草稿未出现在查看器，保存 `viewer-qa-1` 后可刷新切换，原 `main-0` 仍可按地址查看。主世界清单、来源和视图指针逐字节一致。证据见同一浏览器日志。 |
| 来源复核 | 比较固定源的 `definition.csv`、`heightmap.bmp` 和 `provinces.bmp` 与当前存档，并在查看器定位全部 11 个省 | **PASS**，rc=0。3 个推断省的像素全部满足水面阈值。8 个修复省与原始颜色逐像素中心对比，漏盖与多盖均为 0。证据 `/tmp/worldsim-viewer-source-review-2026-11-19.json`。 |
| Windows 访问正式端口 | WSL 中运行 `viewer`，从 Windows 运行 `curl.exe -4 --noproxy '*' http://127.0.0.1:8765/api/map` | **PASS**，rc=0，返回 `main-0`、21,670 省及预期状态 hash。证据 `/tmp/worldsim-viewer-win-8765.json`。 |
| 文档与资源检查 | `python3 tools/check-docs.py`、`node --check src/main/resources/io/mosire/worldsim/viewer/viewer.js`、`git diff --check` | **PASS**，rc=0，11 个必需文件、3 篇 spec、6 篇报告。 |

### 2026-11-19 恢复核对

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量测试与打包 | `mvn -o clean verify` | **PASS**，rc=0，89 tests / 0 failures / 0 errors / 0 skipped。新 jar 为 `target/worldsim-0.1.0-SNAPSHOT.jar`，日志 `/tmp/worldsim-resume-maven-2026-11-19.log`。 |
| 真实世界校验 | `java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify` | **PASS**，rc=0，`verifiedNodes=1`，耗时 10.50 秒，峰值 RSS 1,344,888 KiB。证据 `/tmp/worldsim-resume-world-2026-11-19.json`、`/tmp/worldsim-resume-world-time-2026-11-19.log`。 |
| 真实世界 GM MCP 读路径 | 启动新 jar，初始化 stdio，调用 `tools/list`、`world_overview`、`world_lookup`，关闭 stdin | **PASS**，rc=0。列出 12 个工具，`main-0` hash 与存档一致，读到省 1644 的海陆推断及省 3458 的几何修复证据。主世界入口、视图指针及来源记录逐字节一致。日志 `/tmp/worldsim-resume-stdio-2026-11-19.log`。 |
| 真实地图底图探针 | JDK 21 headless Java2D，从 `main-0` 视图逐块读省属性和几何，对照 86 个同内容的创世快照对象 | **PASS**，rc=0。从 84.27 MB 的几何 JSON 渲染 21,670 省、约 137 万顶点，生成 2048×1536、1.13 MB PNG，读取、渲染和编码共 1.21 秒。证据 `/tmp/worldsim-resume-viewer.dLvS5Q/metrics.json`，预览 `/tmp/worldsim-resume-viewer.dLvS5Q/magna-europa-main-0.png`。浏览器交互与 Windows localhost 连通性 `NOT_RUN`。 |
| 文档检查 | `python3 tools/check-docs.py` 和 `git diff --check` | **PASS**，rc=0，11 个必需文件、2 篇 spec、6 篇报告通过结构检查。 |

### 2026-11-17 文件世界交付验证

| 项目 | 命令或方式 | 结果与证据 |
|---|---|---|
| 全量自动测试 | `mvn clean test` | **PASS**，rc=0，89 tests / 0 failures / 0 errors / 0 skipped；`/tmp/worldsim-final-clean-test.log`。包含跨进程锁、强制中断前后恢复、旧库迁移、权限及真实 stdio MCP 等测试。 |
| 可运行打包 | `mvn -DskipTests package` | **PASS**，rc=0；`target/worldsim-0.1.0-SNAPSHOT.jar`，日志 `/tmp/worldsim-final-package.log`。 |
| 真实世界迁移 | `java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar migrate-sqlite --source world/imports/legacy.db` | **PASS**，rc=0；旧库保留，1 节点与旧 hash 一致，报告 `/tmp/worldsim-real-migration.json`；wall 50.96s，max RSS 1862512 KB。 |
| 真实世界校验 | `java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify` | **PASS**，rc=0，verifiedNodes=1；`/tmp/worldsim-real-verify.json`；wall 10.92s，max RSS 1447904 KB。 |
| 真实地图 MCP | 对导出副本以 jar 运行 stdio MCP，查询来源、fork、预览、应用、保存、全链验证、重启后以相同 requestId 重试 | **PASS**，`/tmp/worldsim-real-mcp-probe.log`；原主世界仍是 `main-0`。此测试先于本轮预览缓存优化；优化后的完整自动测试已通过。 |
| 真实地图预览缓存 | 当前 jar 在世界副本上预览、应用同一批命令并放弃草稿 | **PASS**，`/tmp/worldsim-real-preview-cache.log`；预览 7.78s，随后应用 0.29s，原主世界未修改。 |
| 文档检查 | `python3 tools/check-docs.py`、`git diff --check` | **PASS**，rc=0；11 required / 2 specs / 6 reports；仅检查结构、链接与空白格式。 |

自动化测试的地图为小 fixture，全量 Magna 测试为上表手工进程验证；不把跨平台或物理断电测试计为已验证。

## 风险、未验证与下一步

- 地理参考覆盖 57 个校对地点圈定的主图范围。海外插图仍需独立配准。德波地理缺项已回填，微观地界以原生省形近似。独立旅、实际控制、兵力及装备仍待补证据，其他国家的部署和政治、经济、外交资料待补。证据见[史料查证记录](../superpowers/reports/2026-11-19-1936-source-research.md)。
- 编制查询返回直属数量及差额，仍待接入全树汇总和覆盖率。现有补给模型给出可达性与行军系数，库存、运力和消耗待接入。战斗由 GM 明确裁决，后续按推演需要扩展。[实施计划](../superpowers/plans/2026-11-20-1936-europe-workflow.md)
- 大图在本机的迁移和校验占用约 1.4–1.9 GB RSS，启动与全链验证存在多秒级成本；保存的派生视图增加磁盘占用。可根据使用情况继续优化，不能把本机结果当性能保证。
- 文件存储已做多个提交阶段的强制进程退出与重启测试；真实断电、其他操作系统/文件系统及其目录同步语义 `NOT_RUN`。复制或备份世界应在服务停止、锁释放后进行。
- Maven 构建依赖本地 Maven 仓库 `io.mosire:agentlib-mosire:0.2.5`，干净机器的依赖准备尚未验证。
- 浏览器交互实测于 WSL Chromium，Windows 已验证正式端口的 IPv4 回环 HTTP。其他浏览器实测 `NOT_RUN`。
- 下一步第一条命令 `rg -n '按国家并行|国家与省级状态' docs/superpowers/plans/2026-11-20-1936-europe-workflow.md`。用德波样本提炼史料 Skill，安排 DSH 分国核对版图、编制和有来源的速度，再接人口、政治、经济、外交及 GM 管理的决策回合。[实施计划](../superpowers/plans/2026-11-20-1936-europe-workflow.md)
