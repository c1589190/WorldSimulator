# Mappa Mundi 社区地图导入报告

> **状态**：有效（2026-11-17）
> **日期**：2026-11-17
> **本文性质**：验证证据/导入报告；不是设计正本，也不构成许可意见。
> **用户原话（逐字，2026-11-17）**：
>
> 「我的要求不是直接照搬HOI4的社区地图吗」
>
> 「你不能自己去下载一个吗，非要从本机找？」
>
> **对应计划**：[`../plans/2026-11-17-community-map-import.md`](../plans/2026-11-17-community-map-import.md)

## 数据源

- 项目：**Mappa Mundi - Github Version**
- 仓库：`https://github.com/hoi4fan1234554321/MappaMundi.git`
- 固定提交：`c19441897a6dd6c86ce57edc37b6a3e01b3dc48d`
- 提交日期：2022-11-07
- 下载方式：`tools/fetch-community-map.sh`（sparse clone，仅取导入所需文件）
- 本地 raw 目录：`runtime/imports/MappaMundi`（约 118 MB，gitignored）
- 读取文件：`map/provinces.bmp`、`map/definition.csv`、`history/states`、`map/strategicregions`、`localisation`、`map/supply_nodes.txt`
- 未读取用户本机 Steam HOI4 安装。

## 许可边界

Mappa Mundi 仓库根目录没有标准 LICENSE；README 提供 credits。当前只做**本地导入验证**，不把 raw BMP/州文件/localisation 或派生 SQLite 世界提交进仓库。未来公开分发前必须确认作者授权/许可，或换成明确开放许可的数据源。

## 实现

- `Bmp24Reader`：读取 24-bit 未压缩 BMP，处理 bottom-up/top-down 行序与 4 字节行填充。
- `RasterProvincePolygonizer`：
  - 从像素色块抽取省边界有向边；
  - 按省拼接 ring；
  - 去共线顶点；
  - 按 ring 嵌套识别 shell/hole，支持多 part；
  - 用 JTS `GeometryFixer` 修复少量像素交叉，最终由 `WorldMapValidator` 校验；
  - 同时从像素边界统计省邻接和边界长度。
- `Hoi4CommunityMapImporter`：解析 definition/state/strategic region/localisation/supply nodes，构建 province/state/region/supply hub/adjacency。
- CLI：`import-hoi4`。

## 全量导入结果

命令：

```bash
java -Xmx8g -jar target/worldsim-0.1.0-SNAPSHOT.jar import-hoi4 \
  --source runtime/imports/MappaMundi \
  --db runtime/worlds/mappa-mundi.db --reset
```

结果：

```text
width=5120 height=2560
definitionProvinces=21094
usedProvinces=20753
states=18246
regions=1
adjacencies=58255
supplyHubs=1
rings=25577
repairedPolygons=244
warnings=5
stateHash=sha256:1baa62ef125c524d37d1972777f00ff5f577dc8141cf59d16c1d5f4ec843cdc8
```

耗时（单机单次，含导入、genesis 保存、verifyAll）：

```text
import=23104 ms
genesisSave=20379 ms
verifyAll=20343 ms
wall=64.09 s
max RSS=2,101,036 KB (~2.1 GB, -Xmx8g)
database=31 MB
```

警告：

```text
state 文件无 state block: 13046-Silat.txt
state 文件无 state block: 16457-Japansate.txt
state 文件无 state block: 1892-Cannes.txt
state 文件无 state block: 9858-South Rodi.txt
4 个省没有 state 归属，归入 unassigned-state
```

这 4 个州文件本身截断/为空；导入器没有伪造归属，按 `unassigned-state` 处理。

## 回放与地址验证

```text
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify --db runtime/worlds/mappa-mundi.db
=> rc=0, verifiedNodes=1

java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar replay --db runtime/worlds/mappa-mundi.db --node main-0
=> rc=0, provinces=20753, validated=true
```

地址解析示例：

```text
main-0/map/province/3838   -> province:3838, display "Ajáccio"
main-0/map/state/1         -> state:1, display "Ajaccio", provinceCount=1
main-0/map/region/1        -> region:1, provinceCount=20753
main-0/map/supplyHub/supply-101 -> supplyHub:supply-101, level=1
```

## 已知限制

- 数据源是 2022 年的 HOI4 1.12 时代 Mappa Mundi，不是 1.19 同步快照。
- Mappa 的 `definition.csv` 在该提交中把所有行标为 `land`；海洋语义未从海省类型体现，当前如实导入。
- 该提交只有 1 个战略区域，覆盖 20753 个 used province。
- 4 个州文件损坏；4 个省进入 unassigned state。
- 全量导入是手工/CLI 验证，不放入 `mvn test`（否则每次测试需 2GB 内存和约 1 分钟）；自动测试使用小 BMP fixture。
- raw 资产和生成的 31 MB SQLite 均在 `runtime/`，不进 Git。
