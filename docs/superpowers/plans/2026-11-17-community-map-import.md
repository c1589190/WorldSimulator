# HOI4 社区地图导入计划（Magna Europa Reforged）

> **状态**：已完成（2026-11-17；证据见 `../reports/2026-11-17-magna-europa-import-report.md`）
> **日期**：2026-11-17
> **本文性质**：实现计划（派单责任区）；不是设计正本。
> **用户原话（逐字，2026-11-17）**：
>
> 「我的要求不是直接照搬HOI4的社区地图吗」
>
> 「你不能自己去下载一个吗，非要从本机找？」
>
> 「你不能找个靠谱的源吗」
>
> **历史**：本计划最初使用 Mappa Mundi；其后发现该源只有 1 个 strategic region、占位 supply/rail，已改为 Magna Europa Reforged。Mappa 报告保留为历史证据。

## 责任区

把真正下载的 HOI4 社区地图导入后端的原生 polygon 状态：

1. 选定 **Magna Europa Reforged** 作为当前社区地图数据源；
2. 从下载目录读取 `map/provinces.bmp`、`definition.csv`、`heightmap.bmp`、`adjacencies.csv`、`history/states`、`map/strategicregions`、`localisation/english`、`map/supply_nodes.txt`、`map/railways.txt`；
3. 把 BMP 像素省域转换成多边形 ring（支持 holes/multi-part）；
4. 构建 province/state/region/supply hub/railway/adjacency；
5. 写 SQLite genesis 并回放校验；
6. raw 地图数据放 `runtime/imports/**`（gitignored），仓库只提交下载/导入脚本、解析器和测试 fixture；
7. `SyntheticWorld` 保留为测试/benchmark，不冒充社区地图。

## 数据源与边界

- 仓库：`https://github.com/AzurCrystal/Magna-Europa.git`
- 固定提交：`d7a57759c188a034c49a0e96aceb1702fa76fc74`（2026-10-11）
- 版本：HOI4 1.19.* / mod 0.99.6
- 范围：**欧洲**（不是全球地图）
- 仓库根目录无标准 LICENSE；当前只做本地导入验证，不提交 raw 或派生世界；公开分发前需确认许可。
- 不读取用户本机 Steam HOI4 安装；数据由 `tools/fetch-community-map.sh` 下载到 runtime。
- **不自动生成** strategic region / supply hub / railway：源文件有就导入，没有就 warning/换源/等用户授权。
- 源格式修复允许：heightmap 海陆修正、畸形 state 容错、railway/special adjacency 按源格式解析；修复需 warning/stats，不得伪造归属。

## 验收

- 下载脚本能拉取 Magna 所需文件；
- Java importer 在 tiny BMP fixture 上生成 polygon 省、state、region、supply hub、railway、special adjacency；
- 对 Magna 全量导入产出 2 万级 province，并能保存 genesis、回放、`verify`；
- 地址可解析 province/state/region/supplyHub/railway；
- `mvn clean test`、CLI 全量导入、文档检查有实测 rc/计数。

## 完成结果

- 已从 GitHub 下载 Magna Europa Reforged 固定提交，raw 放 `runtime/imports/MagnaEuropa`（约 123 MB，gitignored）；
- 全量导入 `21670` 个 used province、`2986` 个 state、`253` 个 region、`65196` 条邻接、`674` 个 supply hub、`1921` 条 railway；
- `rings=22241`、`repairedPolygons=8`、`inferredSeaProvinces=3`、`warnings=1`；
- `main-0` genesis 保存并 `verifyAll=1` 通过；
- 地址 `province/1`、`state/1`、`region/1`、`supplyHub/supply-249`、`railway/railway-0001` 均可解析；
- `mvn clean test` 68 tests 全绿；
- 详细计时、hash、警告与限制见 Magna 导入报告。
