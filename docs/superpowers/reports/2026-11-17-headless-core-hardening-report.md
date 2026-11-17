# Headless Core 硬化验证报告

> **状态**：有效（2026-11-17）
> **日期**：2026-11-17
> **本文性质**：验证证据/工程报告；不是设计正本，不构成性能 SLA。
> **用户原话（逐字，2026-11-17）**：
>
> 「额，然后继续？」
>
> **对应计划**：[`../plans/2026-11-17-headless-core-hardening.md`](../plans/2026-11-17-headless-core-hardening.md)

## 范围

本报告只验证 headless core 的规模、schema 和并发边界：

- SQLite `user_version` 与版本门禁；
- 300/500 省合成 polygon 世界生成、校验、保存、回放；
- 两个真实 JVM 对同一 `(branch,tick)` 的写入竞争；
- 不涉及 ORBAT/INFO/MCP/渲染。

## 环境

- 仓库：`~/WorldSimulator`
- 运行环境：WSL
- JDK：21
- 构建：Maven
- 数据库：SQLite JDBC 3.54.1.0，WAL，`busy_timeout=5000`
- 时间：2026-11-17 11:33–11:40 UTC

## Schema

- `SqliteTimelineStore.SCHEMA_VERSION = 1`
- 新库初始化后 `PRAGMA user_version = 1`
- `user_version > 1` ⇒ `TIMELINE_SCHEMA_TOO_NEW`
- `user_version = 0` 的空库/旧开发库 ⇒ 建立 v1 表并抬版本
- 构造初始化失败会关闭已打开连接，避免测试/进程残留锁

## 规模结果

### 300 省（20×15）

命令：

```bash
java -jar target/worldsim-0.1.0-SNAPSHOT.jar benchmark \
  --db /tmp/worldsim-bench/bench.db --reset --columns 20 --rows 15
```

结果：

```text
provinces=300, states=15, regions=5, adjacencies=565, validationIssues=0
generate=130 ms, validate=36 ms, genesisSave=139 ms, deltaSave=117 ms, replay=43 ms
```

### 500 省（25×20）

命令：

```bash
java -jar target/worldsim-0.1.0-SNAPSHOT.jar benchmark \
  --db /tmp/worldsim-bench500/bench.db --reset --columns 25 --rows 20
```

结果：

```text
provinces=500, states=20, regions=5, adjacencies=955, validationIssues=0
generate=152 ms, validate=43 ms, genesisSave=178 ms, deltaSave=109 ms, replay=33 ms
```

说明：时间是单机单次观测，包含 JTS 校验、邻接推导、SQLite 全量 checkpoint 写入和回放；不是吞吐承诺。合成省形是规则矩形，真实不规则 polygon 的 JTS 成本可能更高。

## 多进程写竞争

测试：

```text
src/test/java/io/mosire/worldsim/timeline/SqliteTimelineStoreMultiProcessTest.java
```

做法：

1. 父进程建立 `main-0`；
2. 同时启动两个独立 JVM 的 `StoreProbe`；
3. 两个进程都尝试保存 `main-1`，父节点都是 `main-0`；
4. 断言恰好一个进程成功；
5. 重新打开数据库，确认只有一个 `main-1`、一个 head，且 hash 来自成功进程。

结果：`PASS`（1 test）。证明当前 store 的节点主键/head 检查在真实多进程下不会双写覆盖；不证明高吞吐或多写并发扩展性。

## 回归

```text
mvn clean test           PASS, 55 tests, 0 failures, 0 errors
mvn -DskipTests package PASS
python3 tools/check-docs.py PASS
```

## 剩余边界

- 合成世界不是正式地图数据，也未使用真实多边形；
- 多进程测试只覆盖同节点竞争，未覆盖长事务、写队列、崩溃恢复和 WAL 检查点压力；
- `verifyAll` 仍是逐节点回放，节点规模大时需要增量校验；
- 没有 schema 自动迁移框架，只有 v0→v1 最小迁移；
- ORBAT/INFO/MCP/WebUI 仍未实现。
