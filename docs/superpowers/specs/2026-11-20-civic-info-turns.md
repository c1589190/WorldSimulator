# 国家状态、INFO 与决策回合

> **状态**：有效（首版实现契约）
> **日期**：2026-11-20
> **本文性质**：专题设计
> **依据**：[用户原稿 §20](2026-11-17-worldsim-architecture-design-source.md#23-国家省级状态与决策回合) · [欧洲工作流](../plans/2026-11-20-1936-europe-workflow.md)

新回合的国家及单位决策人、INFO 受众与查看器投影见[节点面板与通用决策人](2026-11-20-node-panels-and-decision-makers.md)。本篇的 `access` 与 `--decision-country` 继续适用于首版国别回合。

## 国家与省份

政治用国家 ID 记录政体、政府和动员状态。人口按省 ID 和普查日保存整数、统计口径和证据。查询选定日期和口径，按地图州、国家实际控制或全图汇总，并报告已覆盖和未覆盖省份。缺项保持未知，来源估计与史料确认保留各自状态。外交以稳定条约 ID 记录双方、类型、正文、开始和可选终止时间及证据。对称关系在查询中按任一方查到，通行与其他条款可保持有方向。时间范围采用左闭右开。事件时间线推进经过条约生效或到期时生成相应事件节点。行军的现行通行许可继续通过既有命令明确裁定。

## INFO

INFO 按包和条目 ID 保存标题、长短文本、可选类型和 JSON 数据、关联实体路径、证据及可见国家名单。地址为 `<node>/info/<pack>/<entry>`，GM 可以读取并用 `putInfo` 写草稿。空受众的条目供 GM 使用。决策入口只读取开放回合基点上受众含本国的条目。模板里的目标是世界内实体路径，输出附当前节点坐标。GM 用回合中各国的精确地址路径名单授予实体查询。授权一个省只给该省公开给该国的实体字段，部队与编制另行授权。

## 回合与工具

GM 在草稿提交 `openTurn`，登记回合 ID、参与国与每国可查询的实体路径。GM 启动独立 stdio，用 `mcp --decision-turn <node>/turn/<id> --decision-country <id>` 将一个会话固定到一个已发布的开放回合及国家。由可信宿主控制命令和世界目录，给决策 Agent 的只有该 MCP 工具面。决策工具只有 `decision_brief`、`decision_lookup`、`decision_submit`。同一个世界沿用文件锁，两个国家的 stdio 会话依次运行。每个国家从共同基点提交一次，只提交决策正文、目标和理由。目标需在其授权中。收件保存在从基点派生的独立提交分支，重复的同一内容返回原回执，收件不会推进正式分支。

GM 用 `world_turn` 查看该基点已提交的提案。用草稿提交 `resolveTurn` 及同批领域命令，由计划器从提交分支抓取完整输入并和裁决说明一起落节点。已结算回合保存完整输入用于回放。事件时间线正常按剧本时刻发布，归档节点与原基点的哈希能够验证。GM 保持原有全部权限。决策人不接触其他国家提交、GM 工具、浏览器服务或世界文件。

## 兼容与验收

可选状态走已有 JSON 快照和增量。旧节点缺字段仍按原测试哈希。用两个国家的不同可见简报、同一处真实地址及演练统计验证权限、历史读取、过期条约、占领统计及人口缺项。两国提交后以 GM 草稿推进一轮，重启核对决策输入、条约和领域状态。史料 Skill 在单独副本验证发现、MCP 调用和回执。


## 调用顺序

- `edit_apply` 的政治载荷用 `{"op":"setPolitics","countryId":"POL","politics":{"regime":…,"government":…,"mobilization":…,"evidence":…}}`。人口用 `putCensus` 的 `records` 数组，每项含 `provinceId`、`date`、`basis`、`people` 和 `evidence`。外交用 `putTreaty` 的 `treaty` 对象，含 `id`、`type`、`first`、`second`、`startsAt`、可选 `endsAt`、`terms` 和 `evidence`。
- `world_population` 用 `date`、`basis` 和可选 `scope` 查询。范围可为省地址、州地址或国家地址。国家统计取该节点已记录的实际控制。国家档案通过 `world_lookup` 查询，条约可用 `<node>/diplomacy/treaty/<id>` 单查。
- 用 `putInfo` 的 `entry` 写记录，`subjects` 和下述访问权限的条目为去掉节点前缀的实体路径。用 `openTurn` 的 `brief` 对象写 `id` 和 `access`。例如 `{"id":"round1","access":{"POL":["country/POL","map/province/19914"]}}`。在正式分支公布节点后，用该节点的完整回合地址为各国启动决策 MCP。
- 决策人用 `decision_brief` 取得当前可见条目。用 `decision_submit` 提交 `text`、`rationale` 和可选 `targets`，目标使用带节点的完整地址。GM 用原回合地址查 `world_turn`，在原节点打开草稿并推进到新时刻，用 `{"op":"resolveTurn","turnAddress":"<基点>/turn/round1","resolution":"裁决说明"}` 和领域变更发布。下个节点的同一回合 ID 可用 `world_turn` 回看。
