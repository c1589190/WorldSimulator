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
> 「有办法修复吗？」
>
> **对应计划**：[`../plans/2026-11-17-community-map-import.md`](../plans/2026-11-17-community-map-import.md)

## 数据源

- 项目：**Mappa Mundi - Github Version**
- 仓库：`https://github.com/N0zza/MappaMundi.git`
- 固定提交：`7a7afbf04ef64b715ff1ebf95380f73a7d02b21a`
- 提交日期：2026-06-27
- 下载方式：`tools/fetch-community-map.sh`（sparse clone，仅取导入所需文件）
- 本地 raw 目录：`runtime/imports/MappaMundi`（约 141 MB，gitignored）
- 读取文件：`map/provinces.bmp`、`map/definition.csv`、`map/heightmap.bmp`、`history/states`、`map/strategicregions`、`localisation`、`map/supply_nodes.txt`
- 未读取用户本机 Steam HOI4 安装。

## 许可边界

Mappa Mundi 仓库根目录没有标准 LICENSE；README 提供 credits。当前只做**本地导入验证**，不把 raw BMP/州文件/localisation 或派生 SQLite 世界提交进仓库。未来公开分发前必须确认作者授权/许可，或换成明确开放许可的数据源。

## 已修复的问题

1. **数据源旧**：从 2022 年第三方镜像切到 2026-06-27 的队内上游 `N0zza/MappaMundi` master。
2. **definition 全 `land`**：读取 `map/heightmap.bmp`，逐像素按 province 统计水面；水面像素过半的 land 省推断为 `sea`。本次修复 `374` 个省。
3. **畸形 state 文件**：
   - 支持 `provinces { ... }` 缺少 `=`；
   - 支持 `provinces` 写在 state 提前闭合括号之外；
   - 支持缺失最终 `}` 的截断 block，保留已写出的 province 列表；
   - 本次导入的 `unassigned-state` 为 0。
4. **像素交叉/自交**：`RasterProvincePolygonizer` 使用 `GeometryFixer` 修复 `171` 个省形；用共享像素边界保证正常相邻省 boundary 精确重合。
5. **raw 数据依赖本机**：改成 `tools/fetch-community-map.sh` 自行从 GitHub sparse clone。

## 源数据审计（回答“HOI4 地图原本不就有吗”）

原版 HOI4 地图确实有完整的 strategic region / supply node / railway 文件；但 Mappa Mundi 这个 GitHub 地图工具源把它们裁剪成了占位数据：

```text
map/strategicregions/      1 个文件，1 个 strategic_region block，id=1，覆盖 21100 个省
map/supply_nodes.txt       4 bytes，仅 "1 1 "，即唯一节点指向 province 1
map/railways.txt           5 bytes，仅 "1 1 1"
```

这不是解析器漏读。导入器按“源数据优先”处理：

- 单 region 原样导入；
- province 1 在 heightmap 中是水面，supply node 被拒绝并 warning；
- railways 文件目前未建模，也没有可用源数据；
- 不自动生成 region/hub/railway 来填补源缺口。

## 实现

- `Bmp24Reader`：读取 24-bit 未压缩 BMP，处理 bottom-up/top-down 行序与 4 字节行填充。
- `Bmp8Reader`：读取 8-bit heightmap 的 palette index。
- `RasterProvincePolygonizer`：
  - 从像素色块抽取省边界有向边；
  - 按省拼接 ring；
  - 去共线顶点；
  - 按 ring 嵌套识别 shell/hole，支持多 part；
  - JTS `GeometryFixer` 修复；
  - 从像素边界统计省邻接和边界长度。
- `Hoi4CommunityMapImporter`：解析 definition/heightmap/state/strategic region/localisation/supply nodes，构建 province/state/region/supply hub/adjacency。
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
definitionProvinces=21100
usedProvinces=21100
states=20707
regions=1
adjacencies=58728
supplyHubs=0
rings=25965
repairedPolygons=171
inferredSeaProvinces=374
warnings=2
stateHash=sha256:cb530d7b4a8750f02c6ebf5fc6120baa227542a253f068dd44493541489f0cd9
```

耗时（单机单次，含导入、genesis 保存、verifyAll）：

```text
import=36868 ms
genesisSave=33410 ms
verifyAll=33197 ms
wall=1:43.74
max RSS=1,634,640 KB (~1.63 GB, -Xmx8g)
database=30 MB
```

警告：

```text
heightmap 推断 374 个 definition=land 省为 sea
supply node 引用无效 land province: 1
```

`map/supply_nodes.txt` 的唯一 supply node 指向 province 1；该省在 heightmap 中为水面（Dead Sea），导入器拒绝伪造陆上补给枢纽，因此当前 `supplyHubs=0`。

## 回放与地址验证

```text
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify --db runtime/worlds/mappa-mundi.db
=> rc=0, verifiedNodes=1

java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar replay --db runtime/worlds/mappa-mundi.db --node main-0
=> rc=0, provinces=21100, validated=true
```

地址解析示例：

```text
main-0/map/province/3838 -> province:3838, land/hills, state=1, "Ajáccio"
main-0/map/province/4416 -> province:4416, land/plains, state=13286, "Trichinopoly"
main-0/map/province/1    -> province:1, sea, stateId=null
main-0/map/state/13286   -> state:13286, "Thanjavur", provinceCount=1
main-0/map/region/1      -> region:1
```

## 剩余限制

- 上游当前只定义 **1 个 strategic region**，覆盖全部省份；导入器如实保留，不按几何自行编造区域。
- `supply_nodes.txt` 的唯一节点指向水面省；当前导入 0 个有效 supply hub，并给出 warning。
- 当前海陆修复按 heightmap 水位推断，未区分 sea/lake；后续可加水体连通性/包围判定。
- strategic region/supply/railway 缺失时**不自动生成**；需要先找含完整数据的社區地图源或由用户明确授权生成。
- 171 个省形由 `GeometryFixer` 修复；渲染/空间查询阶段仍需做几何质量检查。
- 全量导入约 1 分 44 秒、峰值约 1.63 GB；未放入 `mvn test`，自动测试使用小 BMP fixture。
- 上游无标准 LICENSE；raw/派生 DB 只放 gitignored `runtime/`，公开分发前需解决许可。
- ORBAT/INFO/MCP/WebUI 仍未实现。
