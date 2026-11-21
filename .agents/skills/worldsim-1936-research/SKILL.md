---
name: worldsim-1936-research
description: 按用户点名的一个或多个国家初始化或补录 WorldSimulator 1936 剧本。查证国家档案、历史边界、首都、人口、外交和部队驻地与编制，按同一节点核对地址，经 GM MCP 预览、保存和回查。适用于 DSH 等已接入 WorldSimulator GM MCP 的 Agent。
---

# WorldSimulator 1936 剧本查证

## 从请求确定任务

- 从用户本次消息确定国家及范围。可以处理一个国家，也可以在同一任务处理多个国家。用户只说「初始化某国」时，优先核对现有档案、历史国名、首都、国家政治和有据归属，再按资料覆盖补外交、人口和部署；用户只点名某一领域时按该范围做。海外领地、争议边界和未覆盖图幅单列。
- 用用户指定的基点，或工作区 `AGENTS.md` 约定的剧本分支头。若工作区没有约定，用 `timeline_lineage` 查看可用分支。开局日期沿用该剧本，地址都使用这次选定的节点。用 `world_overview` 取得 `stateHash`，先查已有国家和稳定 ID。已存在时复用其 ID 并保留有据字段。查证入口可参考[德波案例](../../../docs/superpowers/reports/2026-11-19-1936-source-research.md)，工作区按可用资料继续检索。

## 查证与录入

- **国家资料。** 用 `world_search`、`world_lookup` 查国家及现有字段。用 `putCountry` 保存名称、别名、首都和证据；有来源的政体、政府和动员用 `setPolitics`。人口用 `putCensus` 保留统计日期和口径，条约用 `putTreaty` 保留双方、时效与条款。缺少必填事实时先记缺项，估计注明方法与证据状态。
- **地名与领土。** 用 `world_geo_lookup` 和 `world_search` 解析历史地名或 WGS84 坐标，再用 `world_lookup` 核对候选省、所属州及现有归属。多候选结合州界、附近地名、底图编号和原始材料核对；覆盖外的坐标只能作线索。用 `world_territory_gaps` 时看 `coverage` 和 `checkedProvinces`。没有内置覆盖时，用 `countryId` 与已独立查证的 `expectedProvinceIds` 核对任务省清单，并记录漏填和归属冲突。用 `setTerritory` 分别记录有据的归属和实控。
- **军队档案。** 先核对开局日的番号和师部驻地，再录有据的下属和实编。用 `putOrbatDocument`、`putUnit` 等现有工具和 `orbat-native-v1`，引用本节点的省份。部署证据注明史料确认的驻地和地图省定位分别有何依据；省定位依赖推断时用 `inferred`，也可保留未知驻地。
- 优先查开局附近的原始地图、档案、年鉴和统计表。记录检索词、原文名、日期、精确页码或表号、来源链接、最终地址和证据状态。底图州史可帮助映射编号，史实另用对应时期资料核对。

## 副本提交

1. 在所给工作区通过 GM MCP 修改存档。按工作区约定继承先前提交；用户要求独立核对时，从所选基点开新分支。多国可用同一草稿，先查边界重叠和同一实体的冲突。将命令写入 `scenario.json`，包含 `base`、`baseHash`、`commands`，需要时附 `forkBranch`、`tick` 或事件时间线选项。
2. 用 `edit_open` 打开草稿，`edit_apply` 按最多 100 条分批预览和应用，逐批核对 hash；最后用 `edit_save` 发布。旧式草稿使用大于基点的 tick，事件草稿按已有事件语义推进并自动分配 tick。史料补录沿用任务的历史日期。
3. 用保存节点重新查询本次国家和有核对依据的变更。给本次任务单独留存 `scenario.json`、`research.md`、`receipt.json`，并向用户报告节点、hash、已覆盖范围和未决项。独立分支需要合并时，用其命令和证据在共同节点再次核对。
