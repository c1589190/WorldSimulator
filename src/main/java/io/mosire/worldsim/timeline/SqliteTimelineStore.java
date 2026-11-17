package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite 时间线存储：{@code timeline_nodes} 保存增量 DAG，{@code checkpoints} 保存派生全量快照。
 *
 * <p>使用单连接 + 方法级同步。首版目标是正确、可测，不追求多进程高吞吐；所有 schema 写入在一个事务内完成，
 * 不会留下“节点已写、checkpoint 没写”的半状态。
 */
public final class SqliteTimelineStore implements TimelineStore {
  private static final String SELECT_COLUMNS =
      "branch, tick, parent_branch, parent_tick, changeset_json, state_hash,"
          + " saved_at_epoch_milli, saved_by";

  private static final String CREATE_NODES = """
      CREATE TABLE IF NOT EXISTS timeline_nodes (
        branch TEXT NOT NULL,
        tick INTEGER NOT NULL,
        parent_branch TEXT,
        parent_tick INTEGER,
        changeset_json TEXT NOT NULL,
        state_hash TEXT NOT NULL,
        saved_at_epoch_milli INTEGER NOT NULL,
        saved_by TEXT NOT NULL,
        PRIMARY KEY (branch, tick),
        CHECK (tick >= 0),
        CHECK ((parent_branch IS NULL AND parent_tick IS NULL)
            OR (parent_branch IS NOT NULL AND parent_tick IS NOT NULL AND parent_tick >= 0)),
        FOREIGN KEY (parent_branch, parent_tick)
          REFERENCES timeline_nodes(branch, tick)
      )
      """;

  private static final String CREATE_CHECKPOINTS = """
      CREATE TABLE IF NOT EXISTS checkpoints (
        branch TEXT NOT NULL,
        tick INTEGER NOT NULL,
        state_json TEXT NOT NULL,
        PRIMARY KEY (branch, tick),
        FOREIGN KEY (branch, tick) REFERENCES timeline_nodes(branch, tick)
      )
      """;

  private final Connection connection;
  private boolean initialized;
  private boolean closed;

  /** 打开/创建一个文件 SQLite 时间线库。构造后自动 initialize。 */
  public SqliteTimelineStore(Path databaseFile) {
    Objects.requireNonNull(databaseFile, "databaseFile");
    Path absolute = databaseFile.toAbsolutePath().normalize();
    try {
      Path parent = absolute.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      throw new TimelineException("TIMELINE_STORE_OPEN_FAILED", "无法创建 SQLite 目录: " + absolute, e);
    }
    this.connection = open("jdbc:sqlite:" + absolute);
    initialize();
  }

  private SqliteTimelineStore(Connection connection) {
    this.connection = connection;
    initialize();
  }

  /** 测试/短生命周期场景使用的内存库；单连接生命周期内有效。 */
  public static SqliteTimelineStore inMemory() {
    return new SqliteTimelineStore(open("jdbc:sqlite::memory:"));
  }

  private static Connection open(String jdbcUrl) {
    try {
      Connection connection = DriverManager.getConnection(jdbcUrl);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys = ON");
        statement.execute("PRAGMA busy_timeout = 5000");
        statement.execute("PRAGMA journal_mode = WAL");
        statement.execute("PRAGMA synchronous = NORMAL");
      }
      return connection;
    } catch (SQLException e) {
      throw new TimelineException("TIMELINE_STORE_OPEN_FAILED", "无法打开 SQLite: " + jdbcUrl, e);
    }
  }

  @Override
  public synchronized void initialize() {
    ensureOpen();
    if (initialized) {
      return;
    }
    transaction(c -> {
      try (Statement statement = c.createStatement()) {
        statement.execute(CREATE_NODES);
        statement.execute(CREATE_CHECKPOINTS);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_timeline_nodes_parent"
            + " ON timeline_nodes(parent_branch, parent_tick)");
      }
      initialized = true;
      return null;
    });
  }

  @Override
  public synchronized boolean isEmpty() {
    ensureOpen();
    return query(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT 1 FROM timeline_nodes LIMIT 1");
           ResultSet result = statement.executeQuery()) {
        return !result.next();
      }
    });
  }

  @Override
  public synchronized Optional<TimelineNode> genesis() {
    ensureOpen();
    return query(connection -> {
      List<TimelineNode> roots = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT " + SELECT_COLUMNS + " FROM timeline_nodes"
              + " WHERE parent_branch IS NULL AND parent_tick IS NULL ORDER BY branch, tick LIMIT 2");
           ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          roots.add(mapNode(result));
        }
      }
      if (roots.size() > 1) {
        throw new TimelineException("TIMELINE_STORE_CORRUPT", "时间线存在多个创世根节点: " + roots.size());
      }
      return roots.stream().findFirst();
    });
  }

  @Override
  public synchronized Optional<TimelineNode> find(NodeRef ref) {
    ensureOpen();
    Objects.requireNonNull(ref, "ref");
    return query(connection -> find(connection, ref));
  }

  @Override
  public synchronized boolean exists(NodeRef ref) {
    ensureOpen();
    Objects.requireNonNull(ref, "ref");
    return query(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT 1 FROM timeline_nodes WHERE branch = ? AND tick = ?")) {
        statement.setString(1, ref.branch().value());
        statement.setLong(2, ref.tick());
        try (ResultSet result = statement.executeQuery()) {
          return result.next();
        }
      }
    });
  }

  @Override
  public synchronized List<TimelineNode> pathToRoot(NodeRef ref) {
    ensureOpen();
    Objects.requireNonNull(ref, "ref");
    return query(connection -> {
      LinkedHashSet<NodeRef> visited = new LinkedHashSet<>();
      List<TimelineNode> reversed = new ArrayList<>();
      Optional<TimelineNode> current = find(connection, ref);
      if (current.isEmpty()) {
        return List.of();
      }
      while (current.isPresent()) {
        TimelineNode node = current.get();
        if (!visited.add(node.ref())) {
          throw new TimelineException("TIMELINE_STORE_CORRUPT",
              "时间线父链成环: " + node.ref());
        }
        reversed.add(node);
        if (node.parent() == null) {
          break;
        }
        current = find(connection, node.parent());
        if (current.isEmpty()) {
          throw new TimelineException("TIMELINE_STORE_CORRUPT",
              "节点 " + node.ref() + " 的父节点不存在: " + node.parent());
        }
      }
      Collections.reverse(reversed);
      return List.copyOf(reversed);
    });
  }

  @Override
  public synchronized List<TimelineNode> nodes(BranchId branch) {
    ensureOpen();
    Objects.requireNonNull(branch, "branch");
    return query(connection -> nodes(connection, branch));
  }

  @Override
  public synchronized Set<BranchId> branches() {
    ensureOpen();
    return query(connection -> {
      LinkedHashSet<BranchId> result = new LinkedHashSet<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT DISTINCT branch FROM timeline_nodes ORDER BY branch");
           ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          result.add(new BranchId(rows.getString("branch")));
        }
      }
      return Collections.unmodifiableSet(result);
    });
  }

  @Override
  public synchronized Optional<NodeRef> head(BranchId branch) {
    ensureOpen();
    Objects.requireNonNull(branch, "branch");
    return query(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT branch, tick FROM timeline_nodes WHERE branch = ? ORDER BY tick DESC LIMIT 1")) {
        statement.setString(1, branch.value());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            return Optional.empty();
          }
          return Optional.of(new NodeRef(
              new BranchId(result.getString("branch")), result.getLong("tick")));
        }
      }
    });
  }

  @Override
  public synchronized void saveNode(TimelineNode node, String checkpointStateJson) {
    ensureOpen();
    Objects.requireNonNull(node, "node");
    if (checkpointStateJson != null && checkpointStateJson.isBlank()) {
      throw new TimelineException("TIMELINE_CHECKPOINT_INVALID", "checkpoint JSON 不得为空白");
    }
    transaction(c -> {
      if (find(c, node.ref()).isPresent()) {
        throw new TimelineException("TIMELINE_NODE_EXISTS", "节点已存在，不能覆盖: " + node.ref());
      }

      if (node.parent() == null) {
        if (node.ref().tick() != 0) {
          throw new TimelineException("TIMELINE_GENESIS_TICK", "创世节点 tick 必须为 0: " + node.ref());
        }
        if (genesis(c).isPresent()) {
          throw new TimelineException("TIMELINE_GENESIS_ALREADY_EXISTS", "时间线已有创世节点");
        }
        if (!nodes(c, node.ref().branch()).isEmpty()) {
          throw new TimelineException("TIMELINE_BRANCH_EXISTS",
              "新创世分支不得已有节点: " + node.ref().branch());
        }
      } else {
        TimelineNode parent = find(c, node.parent())
            .orElseThrow(() -> new TimelineException(
                "TIMELINE_PARENT_NOT_SAVED", "父节点未保存: " + node.parent()));
        if (node.ref().tick() <= parent.ref().tick()) {
          throw new TimelineException("TIMELINE_TICK_NOT_ADVANCING",
              "子节点 tick 必须大于父节点: child=" + node.ref() + " parent=" + parent.ref());
        }
      }

      List<TimelineNode> branchNodes = nodes(c, node.ref().branch());
      if (branchNodes.isEmpty()) {
        if (node.parent() == null) {
          // 创世已在上面单独校验。
        } else if (node.parent().branch().equals(node.ref().branch())) {
          throw new TimelineException("TIMELINE_PARENT_BRANCH_EMPTY",
              "同分支新节点找不到已有 head: " + node.ref().branch());
        }
      } else {
        TimelineNode head = branchNodes.get(branchNodes.size() - 1);
        if (!head.ref().equals(node.parent())) {
          throw new TimelineException("TIMELINE_PARENT_NOT_HEAD",
              "同分支新节点必须接在当前 head 之后: expected=" + head.ref() + " actualParent=" + node.parent());
        }
        if (node.ref().tick() <= head.ref().tick()) {
          throw new TimelineException("TIMELINE_TICK_NOT_ADVANCING",
              "新节点 tick 必须大于分支 head: ref=" + node.ref() + " head=" + head.ref());
        }
      }

      insertNode(c, node);
      if (checkpointStateJson != null) {
        putCheckpoint(c, node.ref(), checkpointStateJson);
      }
      return null;
    });
  }

  @Override
  public synchronized void saveCheckpoint(NodeRef ref, String checkpointStateJson) {
    ensureOpen();
    Objects.requireNonNull(ref, "ref");
    if (checkpointStateJson == null || checkpointStateJson.isBlank()) {
      throw new TimelineException("TIMELINE_CHECKPOINT_INVALID", "checkpoint JSON 不得为空白");
    }
    transaction(c -> {
      if (find(c, ref).isEmpty()) {
        throw new TimelineException("TIMELINE_NODE_NOT_FOUND", "节点不存在: " + ref);
      }
      putCheckpoint(c, ref, checkpointStateJson);
      return null;
    });
  }

  @Override
  public synchronized Optional<String> checkpoint(NodeRef ref) {
    ensureOpen();
    Objects.requireNonNull(ref, "ref");
    return query(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT state_json FROM checkpoints WHERE branch = ? AND tick = ?")) {
        statement.setString(1, ref.branch().value());
        statement.setLong(2, ref.tick());
        try (ResultSet result = statement.executeQuery()) {
          return result.next() ? Optional.of(result.getString("state_json")) : Optional.empty();
        }
      }
    });
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      connection.close();
    } catch (SQLException e) {
      throw new TimelineException("TIMELINE_STORE_CLOSE_FAILED", "关闭 SQLite 失败", e);
    }
  }

  private Optional<TimelineNode> find(Connection connection, NodeRef ref) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT " + SELECT_COLUMNS + " FROM timeline_nodes WHERE branch = ? AND tick = ?")) {
      statement.setString(1, ref.branch().value());
      statement.setLong(2, ref.tick());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(mapNode(result)) : Optional.empty();
      }
    }
  }

  private Optional<TimelineNode> genesis(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT " + SELECT_COLUMNS + " FROM timeline_nodes"
            + " WHERE parent_branch IS NULL AND parent_tick IS NULL ORDER BY branch, tick LIMIT 2");
         ResultSet result = statement.executeQuery()) {
      List<TimelineNode> roots = new ArrayList<>();
      while (result.next()) {
        roots.add(mapNode(result));
      }
      if (roots.size() > 1) {
        throw new TimelineException("TIMELINE_STORE_CORRUPT", "时间线存在多个创世根节点");
      }
      return roots.stream().findFirst();
    }
  }

  private List<TimelineNode> nodes(Connection connection, BranchId branch) throws SQLException {
    List<TimelineNode> result = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT " + SELECT_COLUMNS + " FROM timeline_nodes WHERE branch = ? ORDER BY tick ASC")) {
      statement.setString(1, branch.value());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          result.add(mapNode(rows));
        }
      }
    }
    return List.copyOf(result);
  }

  private static void insertNode(Connection connection, TimelineNode node) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO timeline_nodes (" + SELECT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, node.ref().branch().value());
      statement.setLong(2, node.ref().tick());
      if (node.parent() == null) {
        statement.setNull(3, Types.VARCHAR);
        statement.setNull(4, Types.INTEGER);
      } else {
        statement.setString(3, node.parent().branch().value());
        statement.setLong(4, node.parent().tick());
      }
      statement.setString(5, node.changeSetJson());
      statement.setString(6, node.stateHash());
      statement.setLong(7, node.savedAtEpochMilli());
      statement.setString(8, node.savedBy());
      statement.executeUpdate();
    }
  }

  private static void putCheckpoint(Connection connection, NodeRef ref, String stateJson)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO checkpoints (branch, tick, state_json) VALUES (?, ?, ?)"
            + " ON CONFLICT(branch, tick) DO UPDATE SET state_json = excluded.state_json")) {
      statement.setString(1, ref.branch().value());
      statement.setLong(2, ref.tick());
      statement.setString(3, stateJson);
      statement.executeUpdate();
    }
  }

  private static TimelineNode mapNode(ResultSet result) throws SQLException {
    String parentBranch = result.getString("parent_branch");
    NodeRef parent = parentBranch == null
        ? null
        : new NodeRef(new BranchId(parentBranch), result.getLong("parent_tick"));
    return new TimelineNode(
        new NodeRef(new BranchId(result.getString("branch")), result.getLong("tick")),
        parent,
        result.getString("changeset_json"),
        result.getString("state_hash"),
        result.getLong("saved_at_epoch_milli"),
        result.getString("saved_by"));
  }

  private void ensureOpen() {
    if (closed) {
      throw new TimelineException("TIMELINE_STORE_CLOSED", "SQLite timeline store 已关闭");
    }
  }

  private <T> T query(SqlWork<T> work) {
    try {
      return work.run(connection);
    } catch (SQLException e) {
      throw new TimelineException("TIMELINE_STORE_ERROR", "SQLite 查询失败: " + e.getMessage(), e);
    }
  }

  private <T> T transaction(SqlWork<T> work) {
    try {
      boolean oldAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        throw e;
      } finally {
        connection.setAutoCommit(oldAutoCommit);
      }
    } catch (TimelineException e) {
      throw e;
    } catch (SQLException e) {
      throw new TimelineException("TIMELINE_STORE_ERROR", "SQLite 事务失败: " + e.getMessage(), e);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
