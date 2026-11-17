# Agent 导向世界模型：架构草案 v0.1

> **状态**：历史草案（2026-11-17）· **取代者**：`docs/superpowers/specs/2026-11-17-map-orbat-info-timeline-design.md` · **勿据此开工**。  
> 本文保留静态/动态分离、World Snapshot、Agent 工具预算等早期建议；与现行用户设计冲突处，以用户设计原稿和新专题设计为准。

> 状态：基于 HOI4 地图/地块调研的架构草案，不是最终实现规范  
> 日期：2026-11-17  
> 前置文档：[HOI4 地图、地块与资源社区生态调研](./hoi4-community-map-resources-survey.md)  
> 目标：把“地图数据”提升为 Agent 可查询、可推理、可操作、可回放的世界模型

---

## 1. 设计原则

1. **静态世界与动态世界分离**  
   地形、省界、州界、区域、河流、资源位点是静态/半静态；归属、控制、单位、建筑、补给、天气是动态。

2. **内部 ID 与来源 ID 分离**  
   运行时使用稳定内部键（如 `province:000123` 或 UUIDv5），同时保留 `source_ref` 映射到 HOI4 province/state/region ID。这样导入时可以接受稀疏/异形数据，导出时再做兼容映射。

3. **图是主模型，raster 是几何表示**  
   逻辑查询走实体 + 邻接图；渲染走行段几何 + 图层。不要让 Agent 通过看图猜数据。

4. **读与写不对称**  
   读接口要方便、丰富、可按视野投影；写接口必须窄、结构化、有 revision、可预览、可回滚。

5. **所有事实带 provenance**  
   每个资源、人口、地形、归属至少记录来源：vanilla / DLC / mod / generator / inferred / scenario。动态状态记录 revision 和时间。

6. **Agent 看到的是投影，不是 ground truth**  
   单国 Agent 只能看到自己的可观测信息；裁判/回放/调试视角才看全量。

7. **时间与事件是一等公民**  
   世界状态不是一张静态表，而是 `snapshot + ordered event log`；任何时刻可重建。

8. **默认小工具面**  
   核心 MCP 工具控制在 10–15 个；高级地图编辑/内容创作工具按 profile 加载。

---

## 2. 推荐分层

```text
StaticSnapshot
  ├─ world
  ├─ provinces
  ├─ states
  ├─ strategic_regions
  ├─ countries (scenario shell)
  ├─ edges
  ├─ geometries
  ├─ resource_deposits
  ├─ railways / supply_hubs
  └─ provenance

DynamicState (revision-based)
  ├─ political: state.owner, province.controller, cores/claims, faction
  ├─ military: units, equipment, orders, fronts
  ├─ economy: stockpiles, construction, production
  ├─ infrastructure: building levels/conditions
  ├─ supply: computed supply per province/faction
  ├─ weather: per region/province
  └─ events

ActorProjection
  ├─ visible entities
  ├─ intel estimates + uncertainty
  ├─ known fronts/resources/routes
  └─ permitted tools/actions

AgentGateway
  ├─ read tools
  ├─ render/artifact tools
  ├─ plan/preview/commit tools
  └─ time/event tools
```

---

## 3. 静态快照 Schema

### 3.1 `WorldSnapshot`

```json
{
  "schema_version": "0.1.0",
  "snapshot_id": "hoi4-1.19.4.1-vanilla-20261117",
  "source": {
    "kind": "hoi4_install",
    "game_version": "1.19.4.1",
    "dlc": ["..."],
    "mods": [
      {"id": "some.mod", "version": "1.2.3", "hash": "sha256:..."}
    ],
    "modset_hash": "sha256:...",
    "extractor_version": "0.1.0"
  },
  "map": {
    "width": 5632,
    "height": 2048,
    "pixel_origin": "top-left",
    "map_origin": "bottom-left",
    "wrap_x": null,
    "provinces_bmp_bpp": 24
  },
  "counts": {
    "provinces": 13381,
    "land_provinces": null,
    "sea_provinces": null,
    "lake_provinces": null,
    "states": 1081,
    "strategic_regions": 298,
    "countries": null
  },
  "capabilities": [
    "province_graph",
    "state_resources",
    "victory_points",
    "railways",
    "supply_nodes",
    "weather"
  ],
  "source_hashes": {
    "map/definition.csv": "sha256:...",
    "map/provinces.bmp": "sha256:...",
    "history/states": "sha256:...",
    "map/strategicregions": "sha256:..."
  },
  "provenance": {
    "license_profile": "bring-your-own-game",
    "redistribution_allowed": false,
    "notes": "derived from local game installation"
  }
}
```

### 3.2 `Province`

```json
{
  "key": "province:1234",
  "source_id": 1234,
  "type": "land",
  "terrain": "plains",
  "continent": 1,
  "coastal": true,
  "state_key": "state:64",
  "region_key": "region:23",
  "area_px": 1823,
  "centroid_px": [2330.4, 740.8],
  "bbox_px": [2290, 700, 2381, 781],
  "geometry": {
    "format": "row_runs",
    "runs": [[700, 2290, 2310], [701, 2288, 2314]]
  },
  "controller": null,
  "victory_points": 0,
  "buildings": {
    "fort": 0,
    "naval_base": 0,
    "air_base": 0
  },
  "tags": ["river_border", "chokepoint_candidate"]
}
```

> 几何行段格式参考 `hoi4-agent-tools` 的 `[y, startX, endXExclusive]`：既精确又比每像素对象小得多。`geometry` 可放在单独 artifact/压缩列，不必在普通查询中返回。  
> `map.wrap_x` 表示经度方向是否在**逻辑邻接**上首尾相连；HOI4 的渲染循环与邻接判定是否跨边界需按目标版本实测，不要默认 `true`/`false`。

### 3.3 `State`

```json
{
  "key": "state:64",
  "source_id": 64,
  "name_key": "STATE_64",
  "name": "Brandenburg",
  "category": "metropolis",
  "province_keys": ["province:6521", "province:..."],
  "population_total": 6500000,
  "manpower_pool": 120000,
  "resources": {
    "coal": 40,
    "steel": 12
  },
  "local_supplies": 12.5,
  "building_slots": 12,
  "owner": "GER",
  "initial_controller": "GER",
  "cores": ["GER"],
  "claims": [],
  "victory_points": [
    {"province_key": "province:6521", "value": 50}
  ],
  "impassable": false,
  "history_events": [
    {"date": "1939-01-01", "controller": "GER"}
  ]
}
```

资源/人口是 **来源粒度** 的聚合值。HOI4 的 `state.manpower` 实际是州总人口，`manpower_pool` 是按征兵法案/修正派生出的可用人力；不要把两者混为一谈。如果新引擎需要省/资源点粒度，另建 `resource_deposits`：

```json
{
  "key": "deposit:steel:6521",
  "resource": "steel",
  "province_key": "province:6521",
  "state_key": "state:64",
  "amount": 8,
  "extraction_modifier": 1.0,
  "discovered": true,
  "aggregation": "state",
  "provenance": {"kind": "vanilla", "source_id": 64}
}
```

这样兼容层可以把 `resource_deposits` 聚合回 HOI4 的 `state.resources`，而游戏层可以使用更细粒度。

### 3.4 `StrategicRegion`

```json
{
  "key": "region:23",
  "source_id": 23,
  "name_key": "STRATEGICREGION_23",
  "name": "Italy",
  "province_keys": ["province:..."],
  "land_share": 0.62,
  "sea_share": 0.38,
  "naval_terrain": "ocean",
  "weather_periods": [
    {
      "between": [0, 30],
      "temperature": [-2, 12],
      "weights": {
        "no_phenomenon": 40,
        "rain_light": 20,
        "rain_heavy": 10,
        "snow": 20,
        "mud": 10
      },
      "min_snow_level": 0
    }
  ]
}
```

### 3.5 `Edge`

```json
{
  "from": "province:1234",
  "to": "province:5678",
  "kind": "land",
  "border_px": 217,
  "river_px": 181,
  "river_crossing": true,
  "strait": false,
  "through": null,
  "rule": null,
  "impassable": false
}
```

边的来源必须可区分：

- `border_px`：两省在 raster 中上下左右接触的像素对数；
- `river_px`：共同边界上任一侧为 river 像素的像素对数；
- `river_crossing`：按引擎/规则或阈值判定；
- `strait`/`through`/`rule`：来自 `adjacencies.csv` + `adjacency_rules.txt`；
- `impassable`：来自特殊 adjacency 或州 `impassable`。

### 3.6 `Front`（派生，不落静态快照）

```json
{
  "key": "front:west-north",
  "revision": 42,
  "sides": ["GER", "FRA"],
  "province_keys": ["province:...", "province:..."],
  "length_px": 4830,
  "terrain_mix": {"plains": 0.55, "forest": 0.3, "marsh": 0.15},
  "river_crossings": 6,
  "chokepoints": ["province:..."],
  "supply": {
    "GER": {"status": "green", "hub_distance": 4},
    "FRA": {"status": "yellow", "hub_distance": 8}
  },
  "victory_points_behind": ["province:..."],
  "enemy_estimate": {
    "divisions": [3, 6],
    "confidence": "medium"
  }
}
```

`Front` 的生成算法建议：

1. 取当前所有相邻省对；
2. 保留 controller 属于不同敌对阵营的边；
3. 按地理连续性聚类（共享省/州邻接、河流/区域边界）；
4. 对每个簇计算长度、地形、河流、补给、VP、接触部队；
5. 用稳定 key 命名（如 `front:<actor>:<region>:<ordinal>`），允许跨 tick 跟踪。

---

## 4. 动态状态与事件溯源

### 4.1 Revision

每个动态状态变更产生一个不可变 revision：

```json
{
  "revision": 42,
  "time": "1936-06-01T00:00:00Z",
  "actor": "GER",
  "action_id": "orders/7f3c",
  "ops": [
    {"op": "set_province_controller", "province": "province:3314", "value": "GER"},
    {"op": "move_unit", "unit": "unit:...", "to": "province:3314"}
  ],
  "effects": {
    "supply_recomputed": true,
    "fronts_updated": ["front:west-north"]
  },
  "source": "gameplay"
}
```

约束：

- `revision` 全局单调递增；
- 所有写操作检查 `expected_revision`；
- 先写事件日志，再更新 materialized view；
- 支持从任意 snapshot + revision 重放；
- 随机事件使用 `rng_seed + revision + actor` 确定性派生。

### 4.2 时间模型

建议支持三种接口：

- `time_step(days: int)`：推进固定天数，事件触发时可选暂停；
- `pause_on_event(predicate)`：在特定事件类型/条件暂停；
- `resolve_event(event_id, choice)`：处理需要决策的事件。

Agent 的行动窗口可以是每日或每周，不必暴露 HOI4 的小时 tick。内部可保留更高精度，但 Agent 观测只按日/周聚合。

---

## 5. Agent 投影与视野

### 5.1 ActorProjection

```json
{
  "actor": "GER",
  "revision": 42,
  "as_of": "1936-06-01",
  "known_world": {
    "own_provinces": ["province:..."],
    "visible_provinces": ["province:..."],
    "known_enemy_units": [
      {"unit": "unit:...", "province": "province:...", "estimate": "3 divisions", "confidence": 0.6}
    ]
  },
  "fronts": ["front:west-north"],
  "alerts": [
    {"kind": "supply_low", "province": "province:...", "severity": "warning"}
  ]
}
```

投影规则：

- 自有领土/单位：精确；
- 盟友：按协议/共享情报；
- 敌方：只返回已知或估计，带 confidence/age；
- 中立：公开地理/资源已知，军事未知；
- 裁判/调试：full ground truth。

### 5.2 观测预算

- 单次 tool result 默认不超过 4–8k tokens；
- 实体查询默认 20–100 条，分页；
- 州/区域详情默认限字段；
- 全量 catalog/大图只以 artifact handle 返回；
- 聚合视图优先于列表：front/resource cluster/route corridor；
- 每次返回都带 `revision` 和 `as_of`，避免 Agent 用过期状态做决策。

---

## 6. 行动模型

### 6.1 两类写操作必须分开

| 类别 | 例子 | 权限 | 频率 | 校验 |
|---|---|---|---|---|
| Gameplay orders | 移动军队、进攻、建设、生产、外交 | 玩家/国家 Agent | 每 tick | 规则/资源/视野 |
| Authoring edits | 改省界、建州、改资源、改地形 | 世界作者/裁判 | 低频 | 地图合法性/引用完整性 |

不要让同一个工具既能“移动师”又能“删除省”。两类操作使用不同的 revision 域和权限。

### 6.2 ActionEnvelope

```json
{
  "request_id": "req-7f3c",
  "actor": "GER",
  "expected_revision": 42,
  "as_of": "1936-06-01",
  "intent": {
    "kind": "move_units",
    "units": ["unit:101", "unit:102"],
    "to": "province:3314",
    "posture": "defend"
  },
  "constraints": {
    "max_delay_days": 7,
    "avoid_rivers": false
  }
}
```

`orders_preview` 返回：

```json
{
  "valid": true,
  "revision": 42,
  "route": ["province:...", "province:..."],
  "estimated_days": 5,
  "costs": {"supply": 22, "attrition": 0.03},
  "risks": [
    {"kind": "river_crossing", "province": "province:...", "penalty": 0.25},
    {"kind": "supply_gap", "province": "province:...", "days": 2}
  ],
  "conflicts": [],
  "diff_preview": {
    "units_moved": 2,
    "province_control_changes": 0
  }
}
```

`orders_commit` 必须：

- 校验 `expected_revision`；
- 重新校验前置条件；
- 原子提交；
- 返回新 revision、事件、实际 diff；
- 失败时返回结构化原因，不产生部分状态。

### 6.3 地图编辑操作（authoring）

可以参考 `hoi4-agent-tools` 的操作分类，不要自己发明碎片化接口：

- world：`create_world`
- state：`create_state`, `split_state`, `merge_states`, `move_state_provinces`, `update_state(s)`
- province：`create_province`, `split_province`, `merge_provinces`, `remove_province`, `update_province_definition`
- region：`move_region_provinces`
- IDs/references：`renumber_entity`, `remap_references`
- derived data：`regenerate_positions`, `rebuild_supply`
- adjacency / supply / rail / positions：add/remove/upsert

每个 authoring 操作应有：

- schema 校验；
- 依赖图/引用扫描；
- 预览 diff；
- 操作后不变量验证；
- 失败整笔回滚；
- 输出 `changes` 与 `warnings`。

---

## 7. World Snapshot Compiler 管线

### 7.1 步骤

1. **Discover**：定位游戏安装、DLC、mod、load order、`replace_path`。
2. **Parse**：
   - `definition.csv`；
   - `provinces.bmp`（24bpp，行扫描）；
   - `rivers.bmp`（8bpp indexed）；
   - `adjacencies.csv`；
   - `history/states/*.txt`；
   - `map/strategicregions/*.txt`；
   - `common/resources/*.txt`、`common/terrain/*.txt`；
   - `map/supply_nodes.txt`、`map/railways.txt`；
   - `map/buildings.txt`、`unitstacks.txt`；
   - localisation。
3. **Resolve**：按 bookmark/date 应用 dated history，生成初始动态状态。
4. **Build entities**：省、州、区域、国家、资源点、建筑、铁路。
5. **Build graphs**：陆地、海洋、铁路/补给、战略区域。
6. **Validate**：见第 8 节。
7. **Emit**：
   - `manifest.json`
   - `entities.parquet` 或 `world.sqlite`
   - `edges.parquet`
   - `geometry.runs.zst`
   - `graphs/*.csr`
   - `localisation.parquet`
   - `quality_report.json`
8. **Index**：名称/ID/坐标/省/州/区域查询索引。
9. **Diff**：与上一快照比较实体数、边界、资源、ID 变化。

### 7.2 缓存键

```text
hash(
  extractor_version,
  schema_version,
  game_version,
  dlc_set,
  [(mod_id, mod_version, file_hash)],
  [(source_path, size, mtime_ns)]
)
```

任一变化都重建；不要用 ctime 或目录 mtime 替代内容哈希。

### 7.3 与 HOI4 工具的关系

- **首选**：用 `hoi4-agent-tools` 的 `map_inspect` 作为**外部 oracle**，核对自研 compiler 的实体数/字段/邻接。
- 如果时间有限，也可以直接把 `hoi4-agent-tools` 作为 Phase 1 的 catalog 服务，自研 runtime 只消费其 artifact；但长期应拥有自己的 schema 和 ETL，避免受其工具版本/输出格式约束。
- **HOI4 Live Tools `mapgraph.py`** 可作为图构建/路线算法的参考实现，但不建议直接 vendoring（会引入其平台/依赖/许可约束）。

---

## 8. 必须校验的不变量

### 8.1 静态地图

- [ ] `provinces.bmp` 是 24bpp、无压缩、宽高为 256 的倍数；
- [ ] 每个 province 颜色唯一；
- [ ] 每个 province 至少 N 个像素（默认 8，按版本可配）；
- [ ] 每个 province 像素连通；
- [ ] 不存在四角相接的 X-crossing；
- [ ] `definition.csv` ID 连续、颜色匹配、类型合法；
- [ ] land province 有 continent；sea province 的 terrain 为 ocean；lake 为 lakes；
- [ ] 每个可玩 land province 应属于一个 state；未分配省作为 warning 单列（不可拥有/不可建设，除非目标版本明确支持）；
- [ ] 每个 state 属于恰好一个 strategic region；
- [ ] 每个 state ID 连续，每个 region ID 连续/稳定；
- [ ] 每个 state 至少有一个 province（或显式标记空/特殊）；
- [ ] 每个 VP 的 province 在对应 state 内；
- [ ] 每个建筑位置/港口/海区引用有效；
- [ ] `adjacencies.csv` 在 `-1` 处停止，边引用有效；
- [ ] 铁路/补给节点引用的省存在且类型正确；
- [ ] 战略区域的省集合不重叠、不遗漏（允许显式未分配列表）。

### 8.2 图

- [ ] 邻接对称；
- [ ] 边不重复；
- [ ] 陆地边两端都是 land（特殊 adjacency 除外）；
- [ ] 海运边至少一端是 sea/lake（按规则）；
- [ ] `river_crossing` 与 `river_px` 阈值一致；
- [ ] 不可通行边不会被路由；
- [ ] 铁路边/节点连续；
- [ ] supply hub 在陆省；
- [ ] 每个可达陆地聚落至少有路径到首都/港口/补给源，或显式标记孤立。

### 8.3 动态状态

- [ ] controller 是有效国家或 `null`；
- [ ] owner/controller 分离时，资源/建筑归属规则明确；
- [ ] 单位位置在可通行省/海上；
- [ ] 行动不会导致单位重叠/超载（按规则）；
- [ ] 每次写操作 revision 单调；
- [ ] 同一 `request_id` 幂等；
- [ ] 失败不留部分写入；
- [ ] 回放得到相同状态哈希。

### 8.4 质量报告

`quality_report.json` 至少包含：

```json
{
  "critical": [],
  "warnings": [],
  "stats": {
    "provinces_without_state": 0,
    "provinces_without_region": 0,
    "duplicate_colors": 0,
    "x_crossings": 0,
    "tiny_provinces": 0,
    "disconnected_land": 0,
    "missing_vp_names": 0,
    "stale_buildings": 0
  },
  "source_coverage": {
    "definition": true,
    "states": true,
    "regions": true,
    "localisation": true,
    "railways": true
  }
}
```

---

## 9. 建议的目录/服务边界

```text
worldsim/
  ingest/            # HOI4/earth/synthetic -> internal entities
  schema/            # canonical schema, migrations
  validate/          # invariants, quality report
  graph/             # CSR, routing, front aggregation
  snapshot/          # manifests, hashing, diff
  runtime/           # dynamic state, revisions, events, tick
  projection/        # actor-scoped views, fog-of-war
  gateway/           # MCP/HTTP tool server
  render/            # PNG/WebP tile and overlay artifacts
  fixtures/          # 100% synthetic, no game assets
  docs/
```

服务边界建议：

- **Ingest** 只负责“读并规范化”，不执行业务规则；
- **Runtime** 只消费规范快照，不直接读 BMP；
- **Gateway** 只做协议/权限/分页/artifact，不实现规则；
- **Renderer** 只读快照/动态视图，输出 artifact；
- **Validator** 同时服务 ingest 和 authoring。

---

## 10. 里程碑建议

### M0：验证真实数据（1 周）

- 在用户本机安装 HOI4（1.19.4.x）；
- 运行 `hoi4-agent-tools map_inspect`，导出 catalog；
- 统计省/州/区域/资源/VP/铁路/补给节点；
- 记录与本文公开数字的差异；
- 验收：能按省 ID/名称/坐标查询，能渲染一张 overlay。

### M1：自研 Snapshot POC（2–3 周）

- 解析 `definition.csv` + `provinces.bmp` + states + regions；
- 输出 `manifest/entities/edges/geometry`；
- 构建陆地/海洋/铁路图；
- 与 M0 catalog 做 count/edge 对照；
- 验收：100% 省都有类型/地形/州/区域；邻接对称；图路由可用。

### M2：Agent 只读接口（2 周）

- `world_overview`
- `map_query`
- `map_tile`
- `state_get`
- `front_list`
- `route_plan`
- 验收：单次响应 < 8k tokens；大图用 artifact；查询结果带 revision。

### M3：动态世界与行动（3–4 周）

- revision/event log；
- `orders_preview` / `orders_commit`；
- 一个最小 gameplay 循环（如移动 + 控制权 + 补给重算）；
- 失败回滚；
- 验收：任意 action 可重放；同一 request 幂等；diff 与最终状态一致。

### M4：作者工具与生成（3 周）

- authoring op schema；
- 至少 6 个操作：create_state/move_provinces/update_resource/add_railway/rebuild_supply/renumber_entity；
- 地图校验/预览/回滚；
- 合成世界 fixture；
- 验收：一次 authoring 不会留下悬空引用。

### M5：多 Agent / 视野（按需）

- actor projection；
- 情报/不确定性；
- 多国 Agent 回合仲裁；
- 观战/裁判视角。

---

## 11. 待决问题

1. 内部 ID 用整数自增、UUIDv5 还是 `source:type:id`？  
   建议：内部 `key` 用稳定字符串，`source_id` 单独保存；不要直接拿 HOI4 ID 当主键。
2. 资源点是否要省/州双粒度？  
   建议：v1 以州级为主，增加可选 `ResourceDeposit` 扩展；不要伪造省粒度精度。
3. 是否需要在 v1 实现多 Agent/fog-of-war？  
   建议：接口预留 `actor` 和 `visibility` 字段，v1 可先单 Agent + 裁判全视图。
4. 是否要兼容导出回 HOI4？  
   建议：先做内部模型；兼容导出作为独立 adapter，不污染核心 schema。
5. 是否使用 `hoi4-agent-tools` 作为运行时依赖？  
   建议：M0/M1 作为 oracle 和参考；长期只借鉴接口/算法，核心 ETL 自持。
6. 是否允许生成世界中的“历史假设”？  
   建议：每条数据带 `provenance.kind = historical | generated | inferred | balanced`，Agent 和 UI 都必须能区分。

---

## 12. 附录：最小查询示例

### 12.1 按坐标查询

```json
{
  "tool": "map_query",
  "args": {
    "actor": "GER",
    "coordinate": {"kind": "pixel", "x": 2330, "y": 740}
  }
}
```

返回：

```json
{
  "revision": 42,
  "matches": [
    {
      "province_key": "province:1234",
      "source_id": 1234,
      "name": "Berlin",
      "state": {"key": "state:64", "name": "Brandenburg"},
      "region": {"key": "region:23", "name": "Germany"},
      "terrain": "plains",
      "controller": "GER"
    }
  ]
}
```

### 12.2 查询战线

```json
{
  "tool": "front_get",
  "args": {
    "actor": "GER",
    "front": "front:west-north",
    "include": ["terrain_mix", "supply", "enemy_estimate"]
  }
}
```

### 12.3 路线规划

```json
{
  "tool": "route_plan",
  "args": {
    "actor": "GER",
    "from": "province:6521",
    "to": "province:3314",
    "mode": "land",
    "avoid": ["impassable", "enemy_controlled"],
    "weights": {"terrain": 1.0, "river": 2.0, "supply": 0.5}
  }
}
```

返回应包含：

- 路径省列表；
- 总成本；
- 过河/海峡列表；
- 预计天数；
- 补给风险；
- 替代路线（可选）；
- `revision`。

---

## 13. 参考

- [调研主文档](./hoi4-community-map-resources-survey.md)
- [hoi4-agent-tools map docs](https://github.com/klimPaskov/hoi4-agent-tools/blob/HEAD/docs/map.md)
- [hoi4-agent-tools tools](https://github.com/klimPaskov/hoi4-agent-tools/blob/HEAD/docs/tools.md)
- [HOI4 Live Tools](https://github.com/klimPaskov/HOI4-Live-Tools)
- [EarthToHoI4](https://github.com/EdouardSaladier/EarthToHoI4)
- [HOI4 wiki Map modding](https://hoi4.paradoxwikis.com/Map_modding)
- [HOI4 wiki State modding](https://hoi4.paradoxwikis.com/State_modding)
- [HOI4 wiki Strategic region modding](https://hoi4.paradoxwikis.com/Strategic_region_modding)
