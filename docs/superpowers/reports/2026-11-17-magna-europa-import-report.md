# Magna Europa Reforged 社区地图导入报告

> **状态**：有效（2026-11-17）
> **日期**：2026-11-17
> **本文性质**：验证证据/导入报告；不是设计正本，也不构成许可意见。
> **用户原话（逐字，2026-11-17）**：
>
> 「你不能找个靠谱的源吗」
>
> **来源选择**：用户选择 **Magna Europa Reforged**。  
> **对应计划**：[`../plans/2026-11-17-community-map-import.md`](../plans/2026-11-17-community-map-import.md)

## 数据源

- 项目：**Magna Europa: Reforged**
- 仓库：`https://github.com/AzurCrystal/Magna-Europa.git`
- 固定提交：`d7a57759c188a034c49a0e96aceb1702fa76fc74`
- 提交日期：2026-10-11
- 宣称版本：HOI4 **1.19.\***、mod **0.99.6**
- 下载脚本：`tools/fetch-community-map.sh`
- 本地 raw：`runtime/imports/MagnaEuropa`（约 123 MB，gitignored）
- 读取：`provinces.bmp`、`definition.csv`、`heightmap.bmp`、`history/states`、`strategicregions/*.txt`、`supply_nodes.txt`、`railways.txt`、`adjacencies.csv`、`localisation/english`
- 不读取用户本机 Steam HOI4 安装。

## 为什么选它

与之前 Mappa Mundi 源对比：

| 项目 | Mappa Mundi master | Magna Europa Reforged |
|---|---|---|
| 范围 | 全球 | 欧洲 |
| 版本 | 2026 分支，但地图工具占位严重 | HOI4 1.19 / 2026 |
| strategic regions | 1 | **253** |
| supply nodes | 1（且指向水面） | **674 有效导入** |
| railways | 1 行占位 | **1921 有效导入** |
| state 文件 | 少量畸形 | **2986 个，无畸形** |
| 海陆类型 | definition 全 land，需要 heightmap 推断 | definition 自带 land/sea/lake |

用户选择了 **数据完整、当前版本** 的 Magna Europa，接受其**欧洲范围**。这不是全球地图。

## 许可边界

Magna Europa 仓库根目录没有标准 LICENSE。当前只做**本地导入验证**，不提交 raw 地图、州文件、localisation、BMP 或派生 SQLite 世界。公开分发前必须取得授权/确认许可或换源。

## 实现补充

- 新增 `Railway` 模型，进入 `WorldMap`、`WorldChangeSet`、validator、resolver 和 canonical JSON。
- `railways.txt` 按 HOI4 格式解析：`level provinceCount province...`，允许同一铁路路径中非连续重复省。
- `adjacencies.csv` 的特殊邻接进入 `Adjacency`，新增 `through` 字段；不再丢弃 `through`。
- 铁路 validator 检查：
  - 每段省都存在；
  - 不经过海省；
  - 相邻省对必须存在普通或特殊邻接。
- 地图地址支持 `map/railway/<id>`。

## 全量导入结果

命令：

```bash
java -Xmx8g -jar target/worldsim-0.1.0-SNAPSHOT.jar import-hoi4 \
  --source runtime/imports/MagnaEuropa \
  --db runtime/worlds/magna-europa.db --reset
```

结果：

```text
width=4096 height=3072
definitionProvinces=21670
usedProvinces=21670
states=2986
regions=253
adjacencies=65196
supplyHubs=674
railways=1921
rings=22241
repairedPolygons=8
inferredSeaProvinces=3
warnings=1
stateHash=sha256:bfe635a5cd2a095a755fb62d71460896c1033fa77e2a232cd2b0657fb2719656
```

耗时：

```text
import=10439 ms
genesisSave=8457 ms
verifyAll=8218 ms
wall=27.57 s
max RSS=1,553,508 KB (~1.55 GB, -Xmx8g)
database=41 MB
```

唯一 warning：

```text
heightmap 推断 3 个 definition=land 省为 sea
```

## 回放与地址验证

```text
java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify --db runtime/worlds/magna-europa.db
=> rc=0, verifiedNodes=1

java -Xmx6g -jar target/worldsim-0.1.0-SNAPSHOT.jar replay --db runtime/worlds/magna-europa.db --node main-0
=> rc=0, stateId=magnaeuropa, provinces=21670, validated=true
```

地址示例：

```text
main-0/map/province/1           -> land/mountain, state=1117, region=227
main-0/map/state/1              -> "Quebec", provinceCount=23
main-0/map/region/1             -> "Ireland", provinceCount=169
main-0/map/supplyHub/supply-249 -> supplyHub, level=1
main-0/map/railway/railway-0001 -> railway, level=3, provinceCount=3
```

## 源数据优先

- strategic region、supply hub、railway、special adjacency 都来自 Magna 源文件；
- 不自动生成 region/hub/railway；
- 目前只对 `heightmap` 与 `definition` 的 3 个海陆冲突做有 warning 的修复；
- 位置 localisation 使用 `VICTORY_POINTS_*` 作为省显示名，缺失时用 `Province <id>`，不伪造地名。

## 剩余限制

- Magna 是**欧洲地图**，不是全球地图。
- 上游无标准 LICENSE；raw/派生 DB 只在 gitignored `runtime/`。
- 8 个省形由 `GeometryFixer` 修复，仍需渲染/空间查询阶段检查。
- 全量导入未放入 `mvn test`；自动测试使用 tiny BMP fixture。
- 目前只导入静态地图实体；ORBAT/INFO/MCP/WebUI 未实现。
