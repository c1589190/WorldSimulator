# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 11:54 UTC（19:54 CST）
> **当前目标**：导入实际下载的 HOI4 社区地图（Mappa Mundi）到原生 polygon 静态状态与 SQLite 时间线。
> **当前阶段**：Mappa Mundi 全量导入、genesis 保存、回放与地址解析已完成；等待下一层/许可决策。
> **当前责任区**：`docs/superpowers/plans/2026-11-17-community-map-import.md`（已完成）。

## 源码基线

- 仓库：`~/WorldSimulator`，单模块 Maven 工程，JDK 21。
- Git：`main`，跟踪 `origin/main`；远程 `git@github.com:c1589190/WorldSimulator.git`。
- 已推送基线：`0a43236 docs: record static map resolver contract`。
- 本轮未提交改动：HOI4 社区地图 BMP importer、polygonizer、`import-hoi4` CLI、fetch 脚本、测试与文档；提交后以 `git log/status` 为准。
- 运行环境：WSL；人类客户端定位为只读查看器，不做复杂编辑器。
- 邻仓参考基线：
  - `~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；
  - `~/ProjectMosire` 当前工作树；AgentLib 契约以该仓文档为准；
  - DSH `orbat_*` 工作区当前 `sequence=15`，5 份编制文档（上一轮只读侦察）。

## 社区地图数据源

- 来源：**Mappa Mundi - Github Version**
- 仓库：`https://github.com/hoi4fan1234554321/MappaMundi.git`
- 固定提交：`c19441897a6dd6c86ce57edc37b6a3e01b3dc48d`（2022-11-07）
- 下载脚本：`tools/fetch-community-map.sh`
- 本地 raw：`runtime/imports/MappaMundi`（约 118 MB，gitignored）
- 导入数据库：`runtime/worlds/mappa-mundi.db`（约 31 MB，gitignored）
- 许可：上游根目录无标准 LICENSE，仅有 README credits；raw/派生世界暂不提交，公开分发前需确认许可。

## 当前有效文档

- 用户设计原稿（含第四轮社区地图补裁）：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`
- 专题设计：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`
- Headless Core 实现/硬化计划：`docs/superpowers/plans/2026-11-17-headless-core.md`、`docs/superpowers/plans/2026-11-17-headless-core-hardening.md`
- 社区地图导入计划：`docs/superpowers/plans/2026-11-17-community-map-import.md`
- 社区地图导入证据：`docs/superpowers/reports/2026-11-17-mappa-mundi-import-report.md`
- 硬化证据：`docs/superpowers/reports/2026-11-17-headless-core-hardening-report.md`
- 渲染方案比较：`docs/superpowers/reports/2026-11-17-renderer-options.md`
- 设计裁定索引：`docs/superpowers/DESIGN_RULINGS.md`
- 快速构建/CLI：根 `README.md`

## 最近验证

| 项目 | 结果 | 证据 |
|---|---|---|
| 文档结构检查 | `PASS`（`python3 tools/check-docs.py`，rc=0） | 11 required / 2 specs / 4 reports |
| 单测 | `PASS`（`mvn clean test`，rc=0） | 65 tests / 0 failure / 0 error |
| 可运行打包 | `PASS`（`mvn -DskipTests package`，rc=0） | `target/worldsim-0.1.0-SNAPSHOT.jar`（约 16 MB） |
| 下载脚本 | `PASS`（syntax + local sparse test） | `tools/fetch-community-map.sh` 拉取固定提交所需文件 |
| Mappa 全量导入 | `PASS`（rc=0） | 20753 used provinces / 18246 states / 1 region / 58255 adjacencies / 25577 rings / 244 repaired / 1 supply hub |
| Mappa genesis/replay | `PASS`（均 rc=0） | `verifyAll=1`；`main-0` replay 20753 provinces，`validated=true` |
| 地图地址解析 | `PASS` | `main-0/map/province/3838` → `province:3838`；state/region/supplyHub 也可解析 |
| Mappa 导入资源 | `PASS`（观测） | `wall≈64.1s`，max RSS≈2.1 GB（`-Xmx8g`），DB≈31 MB |
| ORBAT/INFO/MCP/WebUI | `NOT_IMPLEMENTED` | 本阶段范围外 |
| 渲染方案 | 未选型 | `docs/superpowers/reports/2026-11-17-renderer-options.md` |

## 已实现

### 社区地图导入

- `Bmp24Reader`：24-bit 未压缩 BMP，支持 bottom-up/top-down 与行填充。
- `RasterProvincePolygonizer`：
  - 从 `provinces.bmp` 的像素色块抽取省边界；
  - 拼接 ring、去共线点、嵌套识别 shell/hole、支持 multi-part；
  - 用 JTS `GeometryFixer` 修复 244 个像素交叉省形；
  - 从像素边界统计邻接和 border length。
- `Hoi4CommunityMapImporter`：
  - 解析 `definition.csv`、`history/states`、`map/strategicregions`、`localisation`、`map/supply_nodes.txt`；
  - 未归属省进入 `unassigned-state`，不伪造成有证据归属；
  - 全部 raw 资产不进 Git。
- CLI：`import-hoi4 --source <dir> --db <path> --reset`。

### 既有 headless core

- 严格 `NodeAddress`、原生 polygon 静态图、JTS 校验、order-preserving `WorldChangeSet`。
- SQLite `timeline_nodes` + `checkpoints`，schema `user_version=1`。
- `TimelineService`：genesis/openNext/openFork/apply/discard/save/load/replay/verifyAll/checkpoint。
- fork-only、saved-only、同 working copy 合并、content hash 自检。
- map entity resolver：`map/province|state|region|supplyHub`。
- 合成 300/500 省 benchmark 与真实多进程写竞争测试仍保留。

## 已记录的关键裁定

- 原生 polygon 是权威几何；渲染可不画 polygon，只显示 `displayPoint`/选中轮廓。
- 地址沿用 Simos 严格 `treeXXX-tickXXX[/<type>[/<id>...]]`。
- 同一节点按顺序合并多次写入；working copy 可 discard/save；只有 saved 节点能推进或 fork。
- 先只能 fork；不做历史节点原地改写/rebase。
- 单单位单节点只能有一个 effective final state。
- INFO 支持任意地址、短/长文本、自定义 typed payload、多包扩展。
- 当前只上 GM；DM/决策人后续再做。
- 不支持 HOI4 导出。
- **必须实际导入可自行下载的 HOI4 社区地图，不能只做合成 fixture，也不能依赖用户本机 HOI4 安装（D-014）。**
- 人类客户端主要是查看器；环境 WSL；渲染选型优先跨平台。

## 未验证 / 风险 / blocker

- Mappa Mundi 严格来说是 2022 年 HOI4 1.12 时代数据，不是 1.19 同步快照。
- 该提交 `definition.csv` 的所有行均为 `land`；海省类型语义未体现。
- 该提交只有 1 个战略区域，覆盖全部 20753 个 used province。
- 4 个州文件截断/为空；4 个省进入 `unassigned-state`（不伪造归属）。
- `244` 个省形通过 `GeometryFixer` 修复；需在渲染/空间查询阶段继续检查几何质量。
- 全量导入内存峰值约 2.1 GB，CLI 应显式 `-Xmx6g` 或更大；导入约 64 秒。
- 全量导入不在 `mvn test` 中（自动测试使用 4×2 BMP fixture）；需持续做手工 smoke。
- 上游无标准 LICENSE；当前 raw/派生 DB 只放 gitignored `runtime/`，公开分发需解决许可。
- 未实现 ORBAT/INFO 运行时、DM 权限、MCP 工具面、WebUI。
- 渲染方案未定；WSL 到 Windows 浏览器路径需实测。

## 下一步

1. 若继续地图层：给导入世界补 provenance/manifest、更多社区地图 fixture、可选 simplification 与几何质量报告。
2. 若继续业务层：先确认 ORBAT 模板/初始编成/运行时实例边界与 INFO 多包协议，再扩展 ChangeSet。
3. 渲染器仍待用户选择；推荐 WSL 后端 + Windows 浏览器 WebGL2 只读 viewer。
