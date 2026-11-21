# 1936 比瑞录入复核与北欧配准扩展

> **状态**：调研输入
> **日期**：2026-11-21
> **本文性质**：调查与验证证据
> **依据**：[领土审计与地图配准契约](../specs/2026-11-19-1936-europe-agent-turns.md#7-边界复核归属调试与师级序列)

## 检查范围

固定底图为 Magna Europa `d7a57759c188a034c49a0e96aceb1702fa76fc74`。起点 `research-1936-3` 的比瑞录入清单见 `runtime/scenario-1936/dsh-research/submissions/research-1936-3/`。旧地理参考的内置归属清单只有德国、波兰和但泽。比瑞分别查询旧节点与导入节点均返回零，旧 API 未给出实际检查省数。

新 API 在相同查询返回 `coverage=none`。将首轮提交中的 83 个比利时省 ID 和 483 个瑞典省 ID 分别作为任务清单，导入前全部报告漏填，导入后两组任务范围内漏填与归属冲突均为 0。把已归比利时的 12389 作为瑞典预期省时，报告一条归属冲突。此项重放核对录入记录与任务清单。两国 14 和 39 个待复核边界省仍沿用首轮史料记录。

## 复现北欧配准

城市点由哈希固定的 Natural Earth 5.1.2 提供，同名底图省号从 Magna 的北欧胜利点本地化提取。保留旧 Miller 仿射，12 个范围地标的投影均落入原始位图的对应省色。旧覆盖与北欧地标凸包取并集。留出城市在覆盖内共有 16 个，其中 12 个直接命中标名省，另有 3 个在 6 图素误差半径之内。吕勒奥的标名省号 18592 与投影落点 19582 显著分歧，留作源图冲突，未新增该别名。由纳尔维克约 5.25 图素的偏差将检索半径从 5 调为 6。旧地图边界图层、仿射和已保存节点数据仍沿用。

使用原始主图参考生成扩展文件，Python 依赖与原准备脚本相同。

```sh
python3 tools/extend-1936-nordic-geography.py \
  --reference runtime/scenario-1936/opening/demo/world/geo-reference.json \
  --magna runtime/imports/MagnaEuropa \
  --places runtime/scenario-1936/sources/ne_10m_populated_places-v5.1.2.geojson \
  --out target/geo-north/prepared
```

输出与验证证据位于 `target/geo-north/prepared/geo-reference.json`、`geo-nordic-check.json` 和 `target/geo-north/verify-mcp.log`。在实图 MCP 中，厄斯特松德坐标返回候选 9797、18566，同名地标可解析为 9797。博登沿用首轮工作记录的城镇坐标，坐标返回 19582、2039。纳尔维克返回包括标名省 19517 在内的多候选。纬度 71° 的探针仍显示覆盖外。

## 证据修正

GM MCP 用两条 `deployUnit` 更新部署证据。厄斯特松德保留原部署地块和已知状态，另记同名底图地标与坐标邻界；博登留在原地图地块 19582，状态改为推定。这两项保存在 `research-1936-4`，`research-1936-3` 的状态哈希不变。留档为 `runtime/scenario-1936/dsh-research/submissions/research-1936-4/`。博登部署地块到相邻陆省 2039 的估算约为 32.83 公里，单位行军速度仍未知。
