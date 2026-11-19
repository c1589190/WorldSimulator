# STATUS.md —— 当前状态

> **更新时间**：2026-11-19 11:29 UTC（19:29 CST）
> **当前目标**：设计地图地名定位、国家归属和 ORBAT 部署的下一阶段闭环。
> **当前阶段**：文件世界、GM MCP 与只读查看器已推送到 `origin/main` 的交付提交 `f28da02`。地理与国家调查完成，已记录真实地图实测和工程建议。
> **责任区与设计**：[查看器实施计划](../superpowers/plans/2026-11-19-map-viewer.md)、[查看器专题设计](../superpowers/specs/2026-11-19-readonly-map-viewer.md)、[地理定位与国家/ORBAT 调查](../superpowers/reports/2026-11-19-geography-country-orbat-recon.md)。

## 源码基线与有效入口

- 仓库 `~/WorldSimulator`，单模块 Maven 工程，JDK 21。交付分支为 `main`，本轮开发起点是 `d9df31f918d1339c533fb5098c5b6ec1412e82a8`。当前版本与工作树以 `git status --short --branch` 为准。
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
- 保存前检查普通邻接是否对应 polygon，并禁止引入新的不一致；特殊邻接单独处理。`verifyAll` 重放每个节点自身 delta 并校验 checkpoint，修复旧审计所列两项缺口。入口：`src/main/java/io/mosire/worldsim/map/PolygonTopology.java:53`、`src/main/java/io/mosire/worldsim/timeline/TimelineService.java:138`。
- `world/provenance.json` 将来源提示与节点/hash 关联，记录来源、推断和几何修复；后代若更改对应字段，查询不再沿用该字段的旧提示。其是附加证据而非第二套状态。入口：`src/main/java/io/mosire/worldsim/app/WorldProvenance.java`。
- 只读地图查看器可用 `viewer [--port 8765]` 独立启动，或用 `mcp --viewer-port 8765` 与 GM MCP 共用进程。支持省 ID/地址搜索、原始几何点选、可见瓦片、三种着色、铁路/补给中心与已保存节点切换。入口见 `src/main/java/io/mosire/worldsim/app/viewer/WorldViewerServer.java` 和 `src/main/resources/io/mosire/worldsim/viewer/index.html`。
- 待实现 ORBAT 原生编制、DSH `mil-org` 交换、单位部署和单节点最终态，以及 INFO 任意合法地址、长短文本和自定义类型。ORBAT 和 INFO 的变化继续走同一 `Command → ChangeSet → TimelineNode`。DM 权限、动态补给计算、离线视图更改的导入保留为后续工作。

## 当前本地世界与来源

- 社区地图是欧洲范围的 **Magna Europa: Reforged**，源 `https://github.com/AzurCrystal/Magna-Europa.git`，固定提交 `d7a57759c188a034c49a0e96aceb1702fa76fc74`，HOI4 1.19.* / mod 0.99.6；raw 在 `runtime/imports/MagnaEuropa`。上游根目录无标准 LICENSE，公开分发前仍需确认；下载脚本 `tools/fetch-community-map.sh`。
- 当前世界为 `world/world.json` 的 `magnaeuropa`，只有 `main-0`，hash `sha256:097b73086f835cd63df788a787c09cd32fd165be896b39b158c85c43139371ff`。21670 省、2986 州、253 战略区域、674 补给中心、1921 铁路、65196 邻接、27504 EntityLinks。
- 只读一致性备份在 `world/imports/legacy.db`；它用于迁移追溯，正常运行不读取。`world/view/current.json` 指向 `main-0` 的可读视图；独立导出位于 `world/exports/bb64c7db-1d4c-4cd2-a408-798552c4a172/`。
- 通过固定源重新导入并复现完全相同的创世 hash，定位 heightmap 推断海省 `1644, 3721, 8372` 和 `GeometryFixer` 修复省形 `3458, 9587, 16241, 16505, 16622, 16923, 17515, 17967`。信息记录在 `world/provenance.json`。2026-11-19 逐项核对原始位图与导入结果，并在查看器验证各省的来源提示。证据见下表。
- 真实地图普通邻接 64387 条，与 polygon 推导的 64387 条一致，特殊邻接 809 条。早期探针只排除 `special-` 前缀，误将 10 条 `kind=special` 计作普通邻接；原始记录核对后已纠正，实际普通拓扑差异为 0。迁移报告 `world/migration-report.json`、证据 `/tmp/worldsim-border-probe.log`。

## 最近验证

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

- 大图在本机的迁移和校验占用约 1.4–1.9 GB RSS，启动与全链验证存在多秒级成本；保存的派生视图增加磁盘占用。可根据使用情况继续优化，不能把本机结果当性能保证。
- 文件存储已做多个提交阶段的强制进程退出与重启测试；真实断电、其他操作系统/文件系统及其目录同步语义 `NOT_RUN`。复制或备份世界应在服务停止、锁释放后进行。
- Maven 构建依赖本地 Maven 仓库 `io.mosire:agentlib-mosire:0.2.5`，干净机器的依赖准备尚未验证。
- 浏览器交互实测于 WSL Chromium，Windows 已验证正式端口的 IPv4 回环 HTTP。其他浏览器实测 `NOT_RUN`。
- 下一步第一条命令 `sed -n '1,120p' docs/superpowers/reports/2026-11-19-geography-country-orbat-recon.md`。先定地名查询和国家持久化的专题契约，再用葡萄牙验证首个国家与编制部署切片。
