# Mappa Mundi / HOI4 社区地图导入计划

> **状态**：已完成（2026-11-17；全量导入证据见 `../reports/2026-11-17-mappa-mundi-import-report.md`）
> **日期**：2026-11-17
> **本文性质**：实现计划（派单责任区）；不是设计正本。
> **用户原话（逐字，2026-11-17）**：
>
> 「我的要求不是直接照搬HOI4的社区地图吗」
>
> 「你不能自己去下载一个吗，非要从本机找？」
>
> **纠正对象**：上一轮只做了 `SyntheticWorld` 规则矩形网格；它是规模测试 fixture，不是用户要求的社区地图。

## 责任区

把真正下载的 HOI4 社区地图导入后端的原生 polygon 状态：

1. 选定 **Mappa Mundi - Github Version** 作为首个社区地图数据源；
2. 从下载目录读取 `map/provinces.bmp`、`map/definition.csv`、`history/states`、`map/strategicregions`、`localisation`、`map/supply_nodes.txt`；
3. 把 BMP 像素省域转换成多边形 ring（支持 holes/multi-part）；
4. 构建 province/state/region/supply hub/adjacency；
5. 写 SQLite genesis 并回放校验；
6. raw 地图数据放 `runtime/imports/**`（gitignored），仓库只提交下载/导入脚本、解析器和测试 fixture；
7. `SyntheticWorld` 保留为测试/benchmark，不冒充社区地图。

## 数据源与边界

- 首选仓库：`https://github.com/hoi4fan1234554321/MappaMundi.git`
- 固定提交：`c19441897a6dd6c86ce57edc37b6a3e01b3dc48d`
- 该仓库根目录**没有标准 LICENSE**；README 只有 credits。当前只做本地导入验证，不把 raw 资产提交进本仓。
- 若未来公开分发导入结果，必须重新确认地图/州数据许可，或替换成明确开放许可的数据源。
- 不读取用户本机 Steam HOI4 安装；数据由 `tools/fetch-community-map.sh` 下载到 runtime。

## 验收

- 下载脚本能在空目录拉取 Mappa Mundi 所需文件；
- Java importer 能在小 BMP fixture 上生成两个 polygon 省、state、region、supply hub 和邻接；
- 对 Mappa Mundi 全量导入产出 2 万级 province，并能保存 genesis、回放、`verify`；
- 地址可解析如 `main-0/map/province/3838`、`main-0/map/state/1`；
- `mvn clean test`、CLI 全量导入、文档检查有实测 rc/计数。

## 完成结果

- 已从 GitHub 下载 Mappa Mundi 固定提交，raw 放 `runtime/imports/MappaMundi`（118 MB，gitignored）；
- 全量导入 `20753` 个 used province、`18246` 个 state、`1` 个 region、`58255` 条邻接、`1` 个 supply hub；
- `rings=25577`、`repairedPolygons=244`、`warnings=5`；
- `main-0` genesis 保存并 `verifyAll=1` 通过；
- 地址 `main-0/map/province/3838`、`state/1`、`region/1`、`supplyHub/supply-101` 均可解析；
- `mvn clean test` 65 tests 全绿（含 importer/polygonizer/CLI fixture 测试）；
- 详细计时、hash、警告与限制见导入报告。
