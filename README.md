# WorldSimulator

Agent 导向的世界模拟器/编辑器。当前版本提供原生 polygon 地图、地名与实体查询、国家归属和控制、ORBAT 编制与单位部署、Simos 风格时间线、GM stdio MCP 和本地只读地图查看器。

## 构建

需要 JDK 21、Maven 以及本地 Maven 仓库中的 `io.mosire:agentlib-mosire:0.2.5`。若尚未安装该依赖，先按邻仓 `~/ProjectMosire` 的构建方式安装 AgentLibMosire 及其父 POM。

```bash
mvn clean test
mvn -DskipTests package
```

生成 `target/worldsim-0.1.0-SNAPSHOT.jar`。运行工作目录中的 `world/` 是唯一默认世界入口；不要把启动工作目录和 jar 所在目录混淆。`world/` 和其中的社区地图数据不进入 Git。

## 启动已有世界

```bash
cd /home/cna/WorldSimulator
# 无参数启动 GM stdio MCP；stdout 是协议流，stderr 用于诊断
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar
```

服务读取当前目录的 `world/world.json`，持有 `world/session.lock` 并加载校验世界。一个世界只能由一个进程持锁；另一个进程会收到 `WORLD_IN_USE`。stdin 关闭时服务退出并释放锁。没有存档时返回明确错误；不会静默生成世界。

`world/` 是文件存档。无需 SQLite 文件即可启动、保存或回放；项目仍包含 SQLite 驱动，用来显式读取旧库迁移，当前没有使用 SQLite 索引。

## 将旧世界迁入 world/

把旧 SQLite 世界及其一致的 WAL 状态放入 `world/imports/`，再执行迁移。下面用 Python SQLite 的在线备份复制本仓的旧库；原库保留：

```bash
mkdir -p world/imports
python3 - <<'PYCODE'
from pathlib import Path
import sqlite3
src = Path('runtime/worlds/magna-europa.db').resolve()
dst = Path('world/imports/legacy.db').resolve()
if dst.exists():
    raise SystemExit('目标已存在，保留原文件')
with sqlite3.connect(src.as_uri() + '?mode=ro', uri=True) as old:
    with sqlite3.connect(dst) as new:
        old.backup(new)
PYCODE
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar migrate-sqlite \
  --source world/imports/legacy.db
```

迁移只接受 `world/imports/` 内的源文件；保留源库，验证父链、增量、检查点和原状态哈希；`world.json` 已存在时拒绝覆盖。迁移报告写入 `world/migration-report.json`。迁移完成后正常启动不会再读源库或重新转换地图。

也可以将 HOI4 社区地图源准备在 `world/imports/` 后直接创建新世界：

```bash
tools/fetch-community-map.sh world/imports/MagnaEuropa
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar init-hoi4 \
  --source world/imports/MagnaEuropa
```

该命令也拒绝覆盖已有世界。Magna Europa 原始资产与派生世界的公开分发许可尚需确认，当前只用于本地世界。

## 世界文件与查看

```text
world/
  world.json                     # 已提交的分支入口和格式版本
  session.lock                   # 进程持有的文件锁
  imports/                       # 显式初始化/迁移输入；运行时不读取
  migration-report.json          # 迁移时的来源与拓扑报告
  provenance.json                # 可选来源/推断提示，关联源节点及哈希
  data/<generation>/
    timeline/<branch>/<node>/    # node.json + changes.json
    snapshots/<id>/snapshot.json # checkpoint 清单
    objects/<category>/<hash>.json  # 可读、按内容哈希共享的分块
  view/current.json              # 当前可读视图的节点、hash 和目录
  view/versions/<id>/map/        # 省属性、几何、州、区域、铁路等 JSON 分块
  exports/<id>/                  # 导出的独立世界目录
```

`world.json` 是提交入口。保存先写入新的不可变对象和节点，最后原子切换入口；重启时只认入口可达的节点。节点坐标是 `(branch, tick)`，旧节点只能 fork，未保存草稿只在内存。创世 checkpoint 是世界恢复基线，清单引用的 checkpoint 必须保留；备份时应在服务停止后保留完整世界目录。`view/` 可删除或重建，不是第二份世界真值；通过 `view/current.json` 找到其实际目录和对应节点。

以下命令在服务未持锁时使用，默认都操作当前目录下的 `world/`：

```bash
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar verify
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar tree
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar resolve \
  --address main-0/map/province/1
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar view --node main-0
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar export
```

`export` 将已提交节点、可达对象及可选来源报告导出到 `world/exports/<id>/`；该目录可复制为另一个运行目录的 `world/`。导出不包含未提交临时文件、源文件和派生视图。当前对已有世界的修改通过 MCP Command 形成新节点；把离线修改过的视图导入成新节点尚未实现，直接更改已保存对象会导致完整性检查失败。

## 打开地图查看器

在包含 `world/` 的工作目录运行：

```bash
java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar viewer
```

在 Windows 或 WSL 浏览器打开 `http://127.0.0.1:8765/`。拖动平移，滚轮或按钮缩放。可输入地名、省 ID 或完整的节点地址。图层支持地形、州、战略区域、国家归属和实际控制着色，叠加铁路省链、补给中心和部署单位。点省份列表或单位标记可查看单位、部署及两份编制。页首选择已保存的时间线节点，也可用 `?node=<branch-tick>` 直接进入某个节点。默认端口可用 `viewer --port <port>` 调整。

「州」和「战略区域」图层合并区内省界，点击色块选中整区。搜索框也可直接输入独立 key `state:<id>` 或 `region:<id>`。详情显示该 key、节点地址和成员省数。

已配准的世界支持多语地名和 `经度,纬度` 搜索。坐标误差范围触及省界时可选候选省。省份详情显示近似 WGS84 位置，点经纬度可在 OpenStreetMap 复核。德波样例可叠加有日期的史料边界。[1936 资料与导入复现](docs/superpowers/reports/2026-11-19-1936-source-research.md)

「归属调试」用粉色标出待补归属的陆省。右侧清单可跳到省份并复制节点地址。世界带有史料范围时，地图及清单按该范围显示；其余世界查全图。可用 `?node=<branch-tick>&layer=owner-gaps` 直达。

同时使用 Agent 与查看器时，在 MCP 客户端的 jar 参数末尾加 `mcp --viewer-port 8765`，两者共用进程与世界锁。查看器通过页首的**刷新**读取新保存节点。启动地址写到 stderr，MCP 的 stdout 保留协议输出。独立查看与组合启动均使用当前工作目录的 `world/`。

## GM MCP

AgentLib 工具调用经过角色、工具和世界资源授权；调用身份由启动端绑定。当前工具：

- `world_overview`、`world_lookup`、`world_search`、`world_validate`：读世界摘要、实体和地图拓扑，通过地名、别名和 ID 查询同一节点下的规范地址。`world_lookup` 默认省略 polygon 顶点。
- `world_geo_lookup` 接受 `name` 或 `longitude` 与 `latitude`，可指定 `node`。单独传完整省份 `address` 可反查近似经纬度和已校对的地名对应。
- `world_territory_gaps` 分页查待补归属的陆省，返回节点地址、州和史料预期国家。可传 `node`、`countryId`、`stateId`、`offset` 和 `limit`。传 `scope="all"` 可查看全图。
- `timeline_tree`、`timeline_diff`、`timeline_verify`：查看和完整验证已保存时间线。
- `edit_open`、`edit_apply`、`edit_save`、`edit_discard`：打开内存草稿、批量预览/应用、保存或放弃。
- `world_view`、`world_export`：重建可读视图，或导出全部已提交分支。
- `orbat_export`：通过已保存的文档或编制节点地址返回 `mil-org` 和损失报告。

例如，运行在同一 WSL 环境、使用 `mcpServers` 配置格式的客户端可以这样启动服务：

```json
{
  "mcpServers": {
    "worldsim": {
      "command": "/bin/bash",
      "args": ["-c", "cd /home/cna/WorldSimulator && exec java -Xmx5g -jar target/worldsim-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

路径按实际安装位置调整。MCP 客户端会启动该进程；如果已手动启动另一个实例，需要先关闭它以释放世界锁。不同客户端的配置文件位置或格式可能不同。

例如先调用 `world_overview` 获得 `main-0` 的 `stateHash`，再调用：

```json
{"base":"main-0","expectedHash":"sha256:..."}
```

将 `edit_open` 返回的 `draftId` 用于 `edit_apply`：

```json
{
  "draftId":"<draftId>",
  "expectedVersion":0,
  "requestId":"preview-1",
  "dryRun":true,
  "commands":[{"op":"patchProvince","id":"1","name":"新名称"}]
}
```

`dryRun` 不改变草稿；正式应用后用返回的草稿版本调用 `edit_save`，提供 `draftId`、`expectedVersion`、新的 `requestId`、目标 `tick` 和原 `baseHash`。保存回执随节点原子提交；连接中断后用相同参数和 requestId 可重试。批量命令支持省份局部修改和地图实体增删改，具体字段见工具 JSON Schema。

完整性验证检查每个节点的增量与检查点。历史数据若存在 polygon 与普通邻接不一致会在 `world_validate` 返回；后续编辑不得新增类似不一致，特殊邻接独立处理。

来源报告为附加证据，不替代时间线状态。`world_overview` 显示源信息和几何修复摘要；`world_lookup` 对报告中匹配的省字段返回 source/status/confidence，当后代节点修改该字段后不再沿用旧提示。

## 国家与单位部署

`world_search` 接受 `query`、可选的 `node` 和 `kind`，例如查询 `{"query":"Lisboa","kind":"province"}`。命中包含稳定 ID、规范地址和上下文。国家、单位、编制文档的地址示例分别为 `scene-1/country/POR`、`scene-1/orbat/unit/por-governo`、`scene-1/orbat/doc/por-governo-actual/node/root`。

用 `putCountry` 定义国家，`setTerritory` 为明确的省 ID 列表分别设置 `owner` 或 `controller`。每条归属记录带来源，预览会列出覆盖的省和被替换的归属。用 `putOrbatDocument` 写 `orbat-native-v1`，或用 `importMilOrg` 导入 DSH 文件内容。再用 `putUnit` 引用两类编制，可用 `deployUnit` 调整同一单位的部署引用。单位查询返回有效编制的规范地址。源文件没给出的员额或装备以未知显示。字段与地址规则见[单位与编制契约](docs/superpowers/specs/2026-11-19-unit-address-and-orbat-format.md)。

## 当前范围

已实现地图与地名查询、国家归属和控制、ORBAT 编制、单位部署与回放、文件时间线、GM MCP 和只读查看器。INFO 运行时、DM 权限、外部经纬度配准和动态行军、战斗、补给在后续阶段。文件存档的进程中断恢复已做进程级验证，跨平台实际断电行为仍需在目标文件系统测试。

设计及状态见 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 和 [`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md`](docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md)。

## License

本仓代码 MIT，见 [`LICENSE`](LICENSE)。
