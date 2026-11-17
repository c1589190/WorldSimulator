# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 12:10 UTC（20:10 CST）
> **当前目标**：修复并重跑实际 HOI4 社区地图（Mappa Mundi）导入。
> **当前阶段**：Mappa Mundi 全量导入与修复完成；源数据缺少完整 region/supply/railway，不自动生成；等待换源/补源或下一层决策。
> **当前责任区**：`docs/superpowers/plans/2026-11-17-community-map-import.md`（已完成）。

## 源码基线

- 仓库：`~/WorldSimulator`，单模块 Maven 工程，JDK 21。
- Git：`main`，跟踪 `origin/main`；远程 `git@github.com:c1589190/WorldSimulator.git`。
- 已推送基线：`7747cc8 docs: record Mappa Mundi import evidence`。
- 本轮未提交改动：heightmap 海陆推断、state 容错修复、最新 Mappa 源、测试与证据更新；提交后以 `git log/status` 为准。
- 运行环境：WSL；人类客户端定位为只读查看器，不做复杂编辑器。
- 邻仓参考基线：
  - `~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；
  - `~/ProjectMosire` 当前工作树；AgentLib 契约以该仓文档为准；
  - DSH `orbat_*` 工作区当前 `sequence=15`，5 份编制文档（上一轮只读侦察）。

## 社区地图数据源

- 来源：**Mappa Mundi - Github Version**
- 仓库：`https://github.com/N0zza/MappaMundi.git`
- 固定提交：`7a7afbf04ef64b715ff1ebf95380f73a7d02b21a`（2026-06-27）
- 下载脚本：`tools/fetch-community-map.sh`
- 本地 raw：`runtime/imports/MappaMundi`（约 141 MB，gitignored）
- 导入数据库：`runtime/worlds/mappa-mundi.db`（约 30 MB，gitignored）
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
| 单测 | `PASS`（`mvn clean test`，rc=0） | 67 tests / 0 failure / 0 error |
| 可运行打包 | `PASS`（`mvn -DskipTests package`，rc=0） | `target/worldsim-0.1.0-SNAPSHOT.jar`（约 16 MB） |
| 下载脚本 | `PASS`（syntax + sparse/local 验证） | 固定 N0zza master 提交；含 `heightmap.bmp` |
| Mappa 全量导入 | `PASS`（rc=0） | 21100 used provinces / 20707 states / 1 region / 58728 adjacencies / 25965 rings / 171 repaired / 374 inferred sea / 0 valid supply hubs |
| Mappa genesis/replay | `PASS`（均 rc=0） | `verifyAll=1`；`main-0` replay 21100 provinces，`validated=true` |
| 地图地址解析 | `PASS` | `province/3838` → land/state1；`province/4416` → land/state13286；`province/1`/`5276` → sea |
| Mappa 导入资源 | `PASS`（观测） | `wall≈1:43.74`，max RSS≈1.63 GB（`-Xmx8g`），DB≈30 MB |
| ORBAT/INFO/MCP/WebUI | `NOT_IMPLEMENTED` | 本阶段范围外 |
| 渲染方案 | 未选型 | `docs/superpowers/reports/2026-11-17-renderer-options.md` |

## 已实现

### 社区地图导入修复

- 数据源从 2022 镜像切到 2026-06-27 上游 `N0zza/MappaMundi` master。
- `Bmp8Reader` + heightmap 水位推断：definition 全 `land` 的源数据中，本次识别 `374` 个 sea 省。
- state 模式容错：
  - `provinces { ... }` 缺少 `=`；
  - `provinces` 写在 state 提前闭合括号之外；
  - 截断 block 保留已写出的 province 列表；
  - 本次全量导入 `unassigned-state` 为 0。
- `GeometryFixer` 修复 `171` 个省形；共享像素边界的正常相邻省仍精确重合。
- `map/supply_nodes.txt` 唯一节点指向水面省，导入器拒绝造陆上补给枢纽，`supplyHubs=0` 并给出 warning。
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

- 上游 Mappa Mundi 当前只有 1 个 strategic region，覆盖全部省份；`supply_nodes.txt` 仅 `1 1`，`railways.txt` 仅 `1 1 1`。原版 HOI4 有完整数据，但该社区源是裁剪/占位版本。
- 源文件缺失的 region/supply/railway **不自动生成**；需要换含完整数据的社区地图源，或由用户明确授权生成并标 `generated=true`。
- 唯一 supply node 指向水面省（province 1 / Dead Sea），当前没有有效 supply hub；不伪造陆上节点。
- 海陆修复按 heightmap 水位多数推断，未区分 sea/lake；水体连通性/包围语义尚未实现。
- `171` 个省形由 `GeometryFixer` 修复，渲染/空间查询阶段仍需做几何质量检查。
- 全量导入约 1 分 44 秒、峰值约 1.63 GB；未放入 `mvn test`，自动测试使用小 BMP fixture。
- 大世界 `WorldMapValidator` 仍是导入/保存/回放的主要耗时；需后续做增量/快速校验。
- 上游无标准 LICENSE；raw/派生 DB 只放 gitignored `runtime/`，公开分发前需解决许可。
- 未实现 ORBAT/INFO 运行时、DM 权限、MCP 工具面、WebUI。
- 渲染方案未定；WSL 到 Windows 浏览器 localhost 路径需实测。

## 下一步

1. 若继续地图层：先审计/换一个包含完整 `map/strategicregions`、`map/supply_nodes.txt`、`map/railways.txt` 的社区地图源；不再默认自动生成。
2. 若继续业务层：确认 ORBAT 模板/初始编成/运行时实例边界与 INFO 多包协议，再扩展 ChangeSet。
3. 渲染器仍待用户选择；推荐 WSL 后端 + Windows 浏览器 WebGL2 只读 viewer。
