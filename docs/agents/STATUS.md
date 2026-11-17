# STATUS.md —— 当前状态

> **更新时间**：2026-11-17 12:53 UTC（20:53 CST）
> **当前目标**：修复第一阶段代码残余 1–4，并把使用边界与代码问题分开。
> **当前阶段**：第一阶段 §8.3 验收与代码残余修复完成；Magna Europa Reforged 导入/回放完成；WorldSimulator MCP 未实现。
> **当前责任区**：`docs/superpowers/plans/2026-11-17-community-map-import.md`（已完成）。

## 源码基线

- 仓库：`~/WorldSimulator`，单模块 Maven 工程，JDK 21。
- Git：`main`，跟踪 `origin/main`；远程 `git@github.com:c1589190/WorldSimulator.git`。
- 已推送基线：`4fb9520 docs: require source-provided regions and logistics`。
- 本轮未提交改动：Magna Europa 源、Railway/special adjacency 模型、fetch/import 脚本、测试、证据；提交后以 `git log/status` 为准。
- 运行环境：WSL；人类客户端定位为只读查看器，不做复杂编辑器。
- 邻仓参考基线：
  - `~/SimulatorMosire` HEAD `7561b23b79d61f4a59a19ff441580523376c489f`；
  - `~/ProjectMosire` 当前工作树；AgentLib 契约以该仓文档为准；
  - DSH `orbat_*` 工作区当前 `sequence=15`，5 份编制文档（上一轮只读侦察）。

## 社区地图数据源

- 来源：**Magna Europa: Reforged**
- 仓库：`https://github.com/AzurCrystal/Magna-Europa.git`
- 固定提交：`d7a57759c188a034c49a0e96aceb1702fa76fc74`（2026-10-11）
- 版本：HOI4 1.19.* / mod 0.99.6
- 范围：**欧洲**
- 下载脚本：`tools/fetch-community-map.sh`
- 本地 raw：`runtime/imports/MagnaEuropa`（约 123 MB，gitignored）
- 导入数据库：`runtime/worlds/magna-europa.db`（约 45 MB，gitignored）
- 许可：上游根目录无标准 LICENSE；raw/派生 DB 暂不提交，公开分发前需确认许可。
- Mappa Mundi 导入报告已标历史；不再作为当前交付源。

## 当前有效文档

- 用户设计原稿：`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`
- 专题设计：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`
- Headless Core 实现/硬化计划：`docs/superpowers/plans/2026-11-17-headless-core.md`、`docs/superpowers/plans/2026-11-17-headless-core-hardening.md`
- 社区地图导入计划：`docs/superpowers/plans/2026-11-17-community-map-import.md`
- Magna 导入证据：`docs/superpowers/reports/2026-11-17-magna-europa-import-report.md`
- 第一阶段收尾审计：`docs/superpowers/reports/2026-11-17-phase1-gap-audit.md`
- Mappa 历史证据：`docs/superpowers/reports/2026-11-17-mappa-mundi-import-report.md`
- 硬化证据：`docs/superpowers/reports/2026-11-17-headless-core-hardening-report.md`
- 渲染方案比较：`docs/superpowers/reports/2026-11-17-renderer-options.md`
- 设计裁定索引：`docs/superpowers/DESIGN_RULINGS.md`
- 快速构建/CLI：根 `README.md`

## 最近验证

| 项目 | 结果 | 证据 |
|---|---|---|
| 文档结构检查 | `PASS`（`python3 tools/check-docs.py`，rc=0） | 11 required / 2 specs / 6 reports |
| 单测 | `PASS`（`mvn clean test`，rc=0） | 72 tests / 0 failure / 0 error |
| 可运行打包 | `PASS`（`mvn -DskipTests package`，rc=0） | `target/worldsim-0.1.0-SNAPSHOT.jar`（约 16 MB） |
| 下载脚本 | `PASS`（syntax + sparse/local 验证） | Magna 固定提交，含 map/states/regions/supply/rail |
| Magna 全量导入 | `PASS`（rc=0） | 21670 provinces / 2986 states / 253 regions / 65196 adjacencies / 674 supply hubs / 1921 railways / 27504 EntityLinks |
| Magna genesis/replay | `PASS`（均 rc=0） | `verifyAll=1`；`main-0` replay 21670 provinces，`validated=true` |
| 地图地址解析 | `PASS` | province/state/region/supplyHub/railway/link 均可解析 |
| Magna 导入资源 | `PASS`（观测） | `wall≈27.77s`，max RSS≈1.66 GB（`-Xmx8g`），DB≈45 MB |
| ORBAT/INFO/MCP/WebUI | `NOT_IMPLEMENTED` | 本阶段范围外 |
| 渲染方案 | 未选型 | `docs/superpowers/reports/2026-11-17-renderer-options.md` |

## 已实现

### 可靠源导入

- 从 Mappa 历史源切换到 **Magna Europa Reforged**，因为 Magna 源实际包含完整 strategic regions / supply nodes / railways。
- `Railway` 模型进入 `WorldMap`、`WorldChangeSet`、validator、resolver、CLI detail 和 canonical JSON。
- `railways.txt` 按 `level provinceCount province...` 解析；允许非连续重复省（真实铁路环/分支）。
- `adjacencies.csv` 特殊邻接导入，`Adjacency` 增加 `through` 字段；validator 检查 through 省存在。
- 铁路 validator 检查省存在、非海省、每段邻接（普通或特殊）。
- source-first：不自动生成缺失的 region/hub/railway。
- 仅 3 个省用 heightmap 修正 definition 海陆冲突，warning 明确记录。
- 新增 State→StrategicRegion 一致性 validator（`STATE_SPANS_REGIONS`）。
- `SupplyHub` 新增 `capacity/range` 可空字段；未知保持 null，不造 0。
- 新增 `EntityLink` 模型、ChangeSet、validator、canonical JSON 和 `map/link/<key>` resolver；Magna 导入生成 27504 条链接。
- `TimelineService.openNext/openFork/save` 新增 `expectedContentHash` 守卫（`TIMELINE_EXPECTED_HASH_MISMATCH` / `TIMELINE_EXPECTED_HASH_INVALID`），冲突零写入。
- 程序不写死地图名称/欧洲：worldId 从 `--source` 目录名派生；测试用 `community-map`。

### 既有 headless core

- 严格 `NodeAddress`、原生 polygon 静态图、JTS 校验、order-preserving `WorldChangeSet`。
- SQLite `timeline_nodes` + `checkpoints`，schema `user_version=1`。
- `TimelineService`：genesis/openNext/openFork/apply/discard/save/load/replay/verifyAll/checkpoint。
- fork-only、saved-only、同 working copy 合并、content hash 自检。
- 地图实体 resolver：`map/province|state|region|supplyHub|railway`。
- 多进程 SQLite 同节点写竞争测试。

## 已记录的关键裁定

- 原生 polygon 是权威几何；渲染可不画 polygon，只显示 `displayPoint`/选中轮廓。
- 地址沿用 Simos 严格 `treeXXX-tickXXX[/<type>[/<id>...]]`。
- 同一节点按顺序合并多次写入；working copy 可 discard/save；只有 saved 节点能推进或 fork。
- 先只能 fork；不做历史节点原地改写/rebase。
- 单单位单节点只能有一个 effective final state。
- INFO 支持任意地址、短/长文本、自定义 typed payload、多包扩展。
- 当前只上 GM；DM/决策人后续再做。
- 不支持 HOI4 导出。
- 必须实际导入可自行下载的 HOI4 社区地图，不能只做合成 fixture，也不能依赖用户本机 HOI4 安装（D-014）。
- 源数据优先，不自动生成已有 region/supply/railway；缺失先审计/换源/等授权（D-015）。
- 当前地图源选用 Magna Europa Reforged（欧洲、HOI4 1.19、完整后勤数据）；Mappa 降为历史对照（D-016）。
- 人类客户端主要是查看器；环境 WSL；渲染选型优先跨平台。

## 代码风险 / 未验证

- 8 个省形由 `GeometryFixer` 修复，3 个省被 heightmap 推断为 sea；仍需视觉/引擎复核。
- `WorldMapValidator` 仍是大图导入/保存/回放的主要耗时；全量导入约 27 秒、峰值约 1.66 GB。
- 没有实现 ORBAT/INFO/DM/MCP/WebUI/渲染器。

## 使用边界（不是代码缺陷）

- 全量地图导入未放入 `mvn test`（测试/CI 使用边界）；自动测试使用 tiny BMP fixture，全量导入是手工/CLI smoke。
- Magna Europa 是**欧洲地图**，不是全球地图；若必须全球，需另行选择数据源或使用正版 HOI4 本机数据。
- 上游无标准 LICENSE；raw/派生 DB 只放 gitignored `runtime/`，公开分发前需解决许可。
- 当前会话可调用宿主提供的 MCP（如 `orbat_*`），但没有 WorldSimulator 自己的 MCP server/tool schema/权限面。

### 尚未实现（阶段外）

- ORBAT/INFO 运行时、DM 权限、WorldSimulator MCP、WebUI、渲染器。

## 下一步

1. 若继续 map 层：补 geometry/heightmap 质量审计与只读 preview；是否接受欧洲范围由用户决定。
2. 若继续业务层：确认 ORBAT 模板/初始编成/运行时实例边界与 INFO 多包协议，再扩展 ChangeSet。
3. 若接 Agent：先引入 AgentLib，做 WorldSimulator 自己的 MCP server/tool schema/permission surface。
