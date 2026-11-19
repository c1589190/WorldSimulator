# 地理定位、国家归属与 ORBAT 调查

> **状态**：调研输入
> **日期**：2026-11-19
> **本文性质**：调查证据与工程建议
> **源码基线**：`f28da0224698c29770692c1f1774c8e1323864d9`
> **用户原话（逐字）**：「接下来是国家、军队系统，以及我发现一个问题：作为AI，你能否通过现有工具结合有限的文字资料标记一个国家，并且这么多地块，你是否有办法确认其中的某一块地是具体的什么地方？也就是目前本系统是否可以方便地结合外部真实地图等；先调查这些功能该怎么做，给我你的设计想法」

## 当前工具与实际世界

定位和标记的实测基于 Magna Europa Reforged 固定提交 `d7a57759c188a034c49a0e96aceb1702fa76fc74`，当前节点 `main-0`。已有 `world_lookup` 按严格地址查实体，查看器按逻辑坐标做 polygon 命中。Agent 需要先从资料或派生视图找到省 ID。地名反查、WGS84 经纬度查询和国家归属字段待实现。[MCP 工具](../../../src/main/java/io/mosire/worldsim/app/WorldTools.java) · [查看器](../../../src/main/java/io/mosire/worldsim/app/viewer/WorldViewerServer.java)

| 地点或线索 | 实测 |
|---|---|
| 里斯本（Lisboa） | `main-0/map/province/19738`。名称、所属 1042 州及源文件中的胜利点相互印证。逻辑显示点为 `(783, 2274)`。 |
| 巴黎（Paris） | `main-0/map/province/19865`。核对了源地名、现有 MCP 查询和查看器坐标点选。 |
| 波士顿（Boston） | `main-0/map/province/19346`，在图幅左上的北美插图。 |
| 编号 1644 | 导入后只有默认省名与 Tunisia 战略区域，可定位省形和区域，具体地名暂无直接证据。 |
| 维塞乌（Viseu） | `main-0/map/province/5291` 对应源胜利点 Viseu。源州文件是 `947-Viseu.txt`，同一州的源本地化键 `STATE_947` 却为 `Borșa`。配对时需保留冲突证据。 |

21,670 个省里有 2,425 个省拥有导入名称，均是陆地省。另有 17,438 个陆地省沿用默认编号名称，共有 15 个导入名称在多个省重复出现。上游还提供跨版本胜利点和州映射，带匹配类别和冲突标记。[源图西欧地名](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/localisation/english/westerneurope_victory_points_l_english.yml) · [上游对应表说明](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/docs/states/README.md)

## 有限文字能标到哪里

用「按 1936 开局标记葡萄牙，首都里斯本」做样例。剧本日期是 1936-01-01，`POR` 的首都字段指向 1042 州，州史确认里斯本胜利点为省 19738。按州史显式 owner 与对应日期覆盖提取到 19 州，展开为 79 个陆地省，包含 Madeira。79 省均与现行存档的州成员一致。本次取静态剧本记录，外部政治地理及剧本脚本效果需要各自的证据。[1936 开局](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/common/bookmarks/the_gathering_storm.txt) · [Lisboa 州史](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/history/states/1042-Lisboa.txt) · [Madeira 州史](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/history/states/1823-Madeira.txt)

在真实世界导出副本上，通过 MCP 预览了 79 条保留既有标签的 `patchProvince`，约 8.7 秒，草稿仍为版本 0。尝试国家归属命令时，现有地图命令的 schema 返回枚举错误。随后丢弃草稿，两个世界的清单均保持原字节。候选标签可以表达整理结果，正式的国家身份、归属、控制与着色需要领域支持。

## 外部地图接入的证据

从源胜利点选出 25 个主图城市，以省内显示点作图上锚点，匹配 Natural Earth v5.1.2 的地名和经纬度。对每个点都用其余 24 个拟合，然后预测它的原始像素坐标并读取 `provinces.bmp` 对应省 ID。坐标误差衡量预测点至省内显示点的距离。

| 拟合方式 | 中位误差（像素） | P90（像素） | 同省命中 |
|---|---:|---:|---:|
| 经纬度直接仿射 | 17.25 | 32.55 | 0/25 |
| Mercator 后仿射 | 13.09 | 21.66 | 1/25 |
| Miller 后仿射 | 2.86 | 5.10 | 20/25 |

Miller 模型在里斯本、巴黎、柏林的留出测试命中对应省，罗马、伊斯坦布尔等 5 个点跨到了其他省。Funchal 额外复测命中。波士顿、纽约、哈利法克斯和 Saint John 都在北美拼图内，用主图变换会落到图外。主图可从 Miller 起步，逐图幅拟合与验收，靠近边界或控制点稀疏处返回候选或未知。[Natural Earth 地名数据](https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-populated-places/) · [上游 Reloaded 投影说明](https://mods.paradoxplaza.com/mods/65009/Windows) · [QGIS 地理配准与残差](https://docs.qgis.org/3.44/en/docs/user_manual/working_with_raster/georeferencer.html)

## 实施建议

1. **先做地名和地址查询。** 从省名、州、区域和源胜利点构建可重建索引，支持别名与同名候选。返回规范地址、实体 ID、所属州/区域和支持定位的证据。逻辑坐标命中直接复用 polygon 查询。原始标签、校核别名和未确认地点各自保留来源。
2. **把国家做成时间线领域。** 国家保存稳定 ID、名称、别名和可选首都省引用。国家层以省 ID 记录主权归属、当前控制，各自附 `source/status/confidence` 和证据日期。州和外部区域可作选择器，预览展开为明确省 ID、缺口与冲突。确认后把解析结果写入同一 `Command → ChangeSet → TimelineNode`。旧地图节点沿用已有格式及 hash。
3. **支持独立地理参考。** 外部 GeoJSON 区域和 CSV 地名使用声明的坐标系、日期和版本。可选配准记录按地图坐标版本与图幅保存控制点、变换、验证残差及适用范围。导入时生成外部叠层供查看或作为归属候选，历史重放使用保存的省 ID 和证据版本。现代边界提供对照，剧本归属使用对应年代的来源。
4. **用 ORBAT 实现部署闭环。** 先导入 DSH `mil-org`，原生编制保存稳定文档与节点 ID、授权值和证据，兼容导出给出损失报告。运行时单位引用国家、编制节点和省，实有人员或装备未知时维持未知。一个单位在每个节点保留一个有效最终态。首版验收导入、部署、查看、保存与历史回放，随后接省邻接移动。Magna 的 `POR_1936` 有 18 条起始位置记录，落在候选葡萄牙的 6 个省，可作为剧本位置参考。[ORBAT 既有设计 §3](../specs/2026-11-17-map-orbat-info-timeline-design.md) · [源剧本单位位置](https://github.com/AzurCrystal/Magna-Europa/blob/d7a57759c188a034c49a0e96aceb1702fa76fc74/history/units/POR_1936.txt)
5. **保持地图性能。** 地形瓦片沿用现有渲染。国家底色和控制边界按变化范围更新相关瓦片。单位用省显示点绘制，按视口读取。国家与部署更新可复用相同的几何、命中索引及地形瓦片。

首个验收切片是「通过地名定位 Lisboa → 以 1936 剧本证据预览并保存葡萄牙 → 在同一世界部署一个已知编制单位 → 切回历史节点」。经纬度能力可逐图幅加入。葡萄牙的来源候选可先用现有省 ID 复现。

## 证据

- `python3 /tmp/worldsim-geo-country-probe-2026-11-19.py` 退出码为 0。数据摘要 `/tmp/worldsim-geo-country-probe-2026-11-19.json`，Natural Earth GeoJSON SHA-256 `fd3fa867a320cbd5c5b6bb5bc550afeec2939fb2cef688e508007282a55ac42f`。正式配准参数、全图普查及外部多边形导入仍待后续验证。[GeoJSON 坐标约定](https://www.rfc-editor.org/rfc/rfc7946.html#section-4) · [Natural Earth 使用条款](https://www.naturalearthdata.com/about/terms-of-use/)
- `python3 /tmp/worldsim-country-mcp-probe-2026-11-19.py` 退出码为 0，日志 `/tmp/worldsim-country-mcp-probe-2026-11-19.log`。本调查记录工程建议。世界数据模型及工具契约的改动在相应专题设计中裁定。
