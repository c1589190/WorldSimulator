# WorldSimulator

Agent 导向的世界模拟器/编辑器。当前实现的是 **headless core**：实际 HOI4 社区地图导入的原生 polygon 世界 + 严格节点地址 + SQLite 时间线。

## 当前状态（2026-11-17）

已实现：

- 严格 Simos 风格地址：`treeXXX-tickXXX[/<type>[/<id>...]]`；
- 原生 `ProvinceShape`（多 polygon part + holes），JTS 校验省形、重叠、显示点、归属和邻接；
- 静态地图实体：province / state / strategic region / supply hub / adjacency；
- 有序 `WorldChangeSet`，同一 working copy 的多次写入合并为一个增量；
- SQLite 时间线：`(branch, tick)` 节点、父子 DAG、ChangeSet JSON、full-state checkpoint；
- `TimelineService`：genesis、open、apply、discard、save、fork、replay/hash 自检；
- 同分支只能从 head 推进；历史节点只能 fork；不做原地改写/rebase；
- SQLite schema `user_version` 门禁与 v0→v1 最小迁移；
- 合成 300/500 省 benchmark；两个真实 JVM 的写竞争测试；
- 静态地图实体 resolver：`map/province|state|region|supplyHub`；
- **Magna Europa Reforged 社区地图导入器**：BMP 省色块 → 原生 polygon/ring/hole/multi-part，state/strategic region/supply hub/railway/special adjacency；
- 源数据优先：不自动生成缺失的 region/hub/railway；海陆冲突、畸形 state 等修复会写 warning/stats；
- 最小 CLI 与 JUnit 测试。

尚未实现：ORBAT、INFO、DM/决策人权限、MCP 工具面、WebUI。人类客户端定位为只读查看器。

## 构建与测试

需要 JDK 21、Maven。

```bash
mvn clean test
mvn -DskipTests package
```

`mvn test` 当前为 72 个测试。

## CLI

### 社区地图导入

```bash
# 只下载导入所需文件；raw 资产落在 runtime/imports/**，不进 Git
tools/fetch-community-map.sh

# Magna Europa Reforged 全量导入（欧洲；建议显式给 heap）
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar import-hoi4 \
  --source runtime/imports/MagnaEuropa \
  --db runtime/worlds/magna-europa.db --reset

# 回放/地址查询
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify \
  --db runtime/worlds/magna-europa.db
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar resolve \
  --db runtime/worlds/magna-europa.db --address main-0/map/province/1
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar resolve \
  --db runtime/worlds/magna-europa.db --address main-0/map/supplyHub/supply-249
```

全量 Magna Europa 实测：`21670` used provinces、`2986` states、`253` regions、`65196` adjacencies、`674` supply hubs、`1921` railways、`27504` EntityLinks、`8` repaired polygons、`3` heightmap 推断 sea。详见导入报告。

### 核心 demo / benchmark

```bash
# 完整 demo：genesis -> 两次写入合并 -> discard -> save -> 历史节点 fork -> 回放校验
java -jar target/worldsim-0.1.0-SNAPSHOT.jar demo --db runtime/demo.db --reset

# 查看分支/节点
java -jar target/worldsim-0.1.0-SNAPSHOT.jar tree --db runtime/demo.db

# 合成 300 省（20×15）polygon 世界，测量生成/校验/保存/回放
java -jar target/worldsim-0.1.0-SNAPSHOT.jar benchmark \
  --db runtime/benchmark.db --reset --columns 20 --rows 15

# 严格地址解析到稳定实体 id
java -jar target/worldsim-0.1.0-SNAPSHOT.jar resolve \
  --db runtime/demo.db --address main-2/map/province/east

# 回放某个节点（checkpoint + delta）并校验
java -jar target/worldsim-0.1.0-SNAPSHOT.jar replay --db runtime/demo.db --node main-2

# 逐节点校验所有分支
java -jar target/worldsim-0.1.0-SNAPSHOT.jar verify --db runtime/demo.db
```

## 时间线语义

```text
main-0  genesis / full checkpoint
main-1  delta from main-0
main-2  delta from main-1
tree2-2 fork from main-1; delta from main-1, independent from main-2
```

- 节点身份是 `(branch, tick)`，不引入 revision 作为节点版本；
- 同一个 working copy 内的写入按顺序进入同一个 ChangeSet；
- `discard` 只丢弃内存，不写库；
- `save` 前校验完整静态状态并计算 content hash；
- 只有已保存节点能作为父节点；同分支父节点必须是 head；
- 历史节点不能原地推进，只能从该节点 fork 新分支；
- 回放从最近祖先 checkpoint 开始应用 delta，并逐节点校验 content hash。

默认 checkpoint 策略：创世、每个分支首节点、以及分支第 N 个节点（`TimelineService` 默认 N=16；CLI demo 用 N=2）。

SQLite schema 当前为 v1；新库自动写 `PRAGMA user_version=1`，更高版本会拒绝打开，v0 空库/旧开发库做最小迁移。

## 代码入口

```text
src/main/java/io/mosire/worldsim/address/   strict NodeAddress
src/main/java/io/mosire/worldsim/map/       polygon map + JTS validation
src/main/java/io/mosire/worldsim/state/     static state + WorldChangeSet
src/main/java/io/mosire/worldsim/timeline/  SQLite store + TimelineService + working copy
src/main/java/io/mosire/worldsim/importer/  HOI4 community-map BMP/state/region importer
src/main/java/io/mosire/worldsim/cli/       sample/synthetic world + headless CLI
tools/fetch-community-map.sh                sparse download of Magna Europa source files
src/test/java/                               JUnit 5 tests
```

## 文档

- 当前状态：[`docs/agents/STATUS.md`](docs/agents/STATUS.md)
- 用户设计原稿：[`docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md`](docs/superpowers/specs/2026-11-17-worldsim-architecture-design-source.md)
- 地图 + ORBAT + INFO + 时间线设计：[`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`](docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md)
- 文档入口：[`docs/README.md`](docs/README.md)
- 实现计划：[`docs/superpowers/plans/2026-11-17-headless-core.md`](docs/superpowers/plans/2026-11-17-headless-core.md)
- 硬化证据：[`docs/superpowers/reports/2026-11-17-headless-core-hardening-report.md`](docs/superpowers/reports/2026-11-17-headless-core-hardening-report.md)
- 社区地图导入计划：[`docs/superpowers/plans/2026-11-17-community-map-import.md`](docs/superpowers/plans/2026-11-17-community-map-import.md)
- 社区地图导入证据：[`docs/superpowers/reports/2026-11-17-magna-europa-import-report.md`](docs/superpowers/reports/2026-11-17-magna-europa-import-report.md)
- Mappa Mundi 历史导入证据：[`docs/superpowers/reports/2026-11-17-mappa-mundi-import-report.md`](docs/superpowers/reports/2026-11-17-mappa-mundi-import-report.md)

## License

本仓代码 MIT，见 [`LICENSE`](LICENSE)。

Magna Europa Reforged raw 资产不在本仓；脚本下载到 gitignored `runtime/imports/**`。该上游仓库当前没有标准 LICENSE，只做本地验证；公开分发导入结果前必须重新确认地图/州数据许可。
