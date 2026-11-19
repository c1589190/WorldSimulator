package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.map.ValidationIssue;
import io.mosire.worldsim.map.PolygonTopology;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.map.WorldMapValidator;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Headless 时间线服务：唯一写路径为 working copy → ChangeSet → saved node。
 *
 * <ul>
 *   <li>创世节点保存全量 checkpoint；
 *   <li>同分支只能从当前 head 推进；
 *   <li>任意已保存节点可 fork 新分支，历史节点不能原地改写；
 *   <li>保存前校验静态状态；保存/回放都以完整状态 content hash 自检；
 *   <li>working copy 不是节点，discard 后不留下任何时间线写入。
 * </ul>
 */
public final class TimelineService {
  public static final BranchId DEFAULT_BRANCH = new BranchId("main");
  public static final int DEFAULT_CHECKPOINT_EVERY = 16;

  private final TimelineStore store;
  private final int checkpointEvery;
  private final ReplayCache cachedReplay = new ReplayCache();

  private static final class ReplayCache {
    private record Entry(StaticWorldState state, boolean validated) {}

    private static final class MapEntry {
      final WorldMap map;
      final Function<StaticWorldState, String> hash;
      boolean validated;

      MapEntry(StaticWorldState state) {
        this.map = state.map();
        this.hash = state.contentHasher();
      }
    }

    private final LinkedHashMap<NodeRef, Entry> states = new LinkedHashMap<>(16, .75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<NodeRef, Entry> entry) { return size() > 12; }
    };
    private final List<MapEntry> maps = new ArrayList<>(2);

    private MapEntry mapFor(StaticWorldState state) {
      for (int index = 0; index < maps.size(); index++) {
        if (maps.get(index).map == state.map()) {
          var found = maps.remove(index);
          maps.addLast(found);
          return found;
        }
      }
      var created = new MapEntry(state);
      maps.addLast(created);
      if (maps.size() > 2) {
        var evicted = maps.removeFirst();
        states.values().removeIf(entry -> entry.state().map() == evicted.map);
      }
      return created;
    }

    String contentHash(StaticWorldState state) {
      return mapFor(state).hash.apply(state);
    }

    StaticWorldState checked(NodeRef ref, Entry entry) {
      if (!entry.validated()) {
        var map = mapFor(entry.state());
        if (!map.validated) {
          requireValidMap(map.map);
          map.validated = true;
        }
        requireValidDomains(entry.state());
        states.put(ref, new Entry(entry.state(), true));
      }
      return entry.state();
    }
  }

  public TimelineService(TimelineStore store) {
    this(store, DEFAULT_CHECKPOINT_EVERY);
  }

  /**
   * @param checkpointEvery 每个分支第 N 个节点写 checkpoint；分支首节点总是写 checkpoint
   */
  public TimelineService(TimelineStore store, int checkpointEvery) {
    this.store = Objects.requireNonNull(store, "store");
    if (checkpointEvery < 1) {
      throw new IllegalArgumentException("checkpointEvery 必须 >= 1: " + checkpointEvery);
    }
    this.checkpointEvery = checkpointEvery;
  }

  public TimelineStore store() {
    return store;
  }

  public TimelineNode createGenesis(StaticWorldState state, String savedBy) {
    return createGenesis(DEFAULT_BRANCH, state, savedBy);
  }

  public TimelineNode createGenesis(BranchId branch, StaticWorldState state, String savedBy) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(state, "state");
    requireSavedBy(savedBy);
    if (!store.isEmpty() || store.genesis().isPresent()) {
      throw new TimelineException("TIMELINE_GENESIS_ALREADY_EXISTS", "时间线已有创世节点");
    }
    requireValidState(state);
    NodeRef ref = new NodeRef(branch, 0);
    TimelineNode node = new TimelineNode(
        ref,
        null,
        WorldChangeSet.emptyJson(),
        state.contentHash(),
        System.currentTimeMillis(),
        savedBy);
    store.saveNode(node, state.canonicalJson());
    return node;
  }

  /** 读取任意已保存节点的完整状态：最近 checkpoint + 增量回放，并逐节点校验 hash。 */
  public StaticWorldState load(NodeRef ref) {
    return load(ref, null);
  }

  /** Interactive reads can reuse previously read and hash-checked states on the requested parent chain. */
  public synchronized StaticWorldState loadCached(NodeRef ref) {
    return load(ref, cachedReplay);
  }

  private StaticWorldState load(NodeRef ref, ReplayCache cache) {
    TimelineNode target = requireSavedNode(ref);
    if (cache != null) {
      var hit = cache.states.get(ref);
      if (hit != null) return cache.checked(ref, hit);
    }
    List<TimelineNode> path = store.pathToRoot(target.ref());
    if (path.isEmpty()) {
      throw new TimelineException("TIMELINE_STORE_CORRUPT",
          "已找到节点但无法构造根路径: " + target.ref());
    }

    Function<StaticWorldState, String> cachedHash = cache == null ? null : cache::contentHash;
    Function<StaticWorldState, String> hash = cachedHash == null ? StaticWorldState::contentHash : cachedHash;
    int start = -1;
    StaticWorldState state = null;
    for (int index = path.size() - 1; index >= 0; index--) {
      TimelineNode candidate = path.get(index);
      var ancestor = cache == null ? null : cache.states.get(candidate.ref());
      if (ancestor != null) {
        state = ancestor.state();
        start = index;
        break;
      }
      var checkpoint = store.checkpointState(candidate.ref(), cachedHash);
      if (checkpoint.isEmpty()) continue;
      state = checkpoint.get();
      requireHash(candidate, hash.apply(state), "checkpoint");
      if (cache != null) cache.states.put(candidate.ref(), new ReplayCache.Entry(state, false));
      start = index;
      break;
    }
    if (start < 0) {
      throw new TimelineException("TIMELINE_CHECKPOINT_MISSING",
          "节点路径上没有 checkpoint，无法重建状态: " + target.ref());
    }

    for (int index = start + 1; index < path.size(); index++) {
      TimelineNode node = path.get(index);
      WorldChangeSet changeSet;
      try {
        changeSet = WorldChangeSet.fromJson(node.changeSetJson());
      } catch (RuntimeException e) {
        throw new TimelineException("TIMELINE_CHANGESET_INVALID",
            "节点 ChangeSet JSON 无法解析: " + node.ref(), e);
      }
      try {
        state = changeSet.apply(state);
      } catch (RuntimeException e) {
        throw new TimelineException("TIMELINE_CHANGESET_APPLY_FAILED",
            "回放节点 ChangeSet 失败: " + node.ref(), e);
      }
      requireHash(node, hash.apply(state), "replay");
      if (cache != null) cache.states.put(node.ref(), new ReplayCache.Entry(state, false));
    }
    if (cache != null) return cache.checked(ref, cache.states.get(ref));
    requireValidState(state);
    return state;
  }

  /** 显式校验某节点重放结果；当前与 {@link #load(NodeRef)} 同义。 */
  public StaticWorldState replay(NodeRef ref) {
    return load(ref);
  }

  /** 逐节点重放，返回验证节点数；用于 CLI/回归检查。 */
  public int verifyAll() {
    store.verifyStorageIntegrity();
    int verified = 0;
    for (BranchId branch : store.branches()) {
      NodeRef previousRef = null;
      StaticWorldState previousState = null;
      for (TimelineNode node : store.nodes(branch)) {
        if (node.parent() == null) {
          if (!WorldChangeSet.fromJson(node.changeSetJson()).isEmpty()) {
            throw new TimelineException("TIMELINE_GENESIS_CHANGESET_INVALID", "创世节点增量必须为空: " + node.ref());
          }
          previousState = load(node.ref());
        } else {
          StaticWorldState parent = node.parent().equals(previousRef) ? previousState : load(node.parent());
          StaticWorldState replayed;
          try {
            replayed = WorldChangeSet.fromJson(node.changeSetJson()).apply(parent);
          } catch (RuntimeException e) {
            throw new TimelineException("TIMELINE_CHANGESET_APPLY_FAILED", "完整校验无法应用增量: " + node.ref(), e);
          }
          requireHash(node, replayed, "full-replay");
          requireValidState(replayed);
          var checkpoint = store.checkpointState(node.ref(), null);
          if (checkpoint.isPresent()) {
            requireHash(node, checkpoint.get(), "checkpoint");
          }
          previousState = replayed;
        }
        previousRef = node.ref();
        verified++;
      }
    }
    return verified;
  }

  /** 从分支 head 打开推进用 working copy。历史/非 head 节点只能走 {@link #openFork}。 */
  public TimelineWorkingCopy openNext(NodeRef savedRef) {
    return openNext(savedRef, null);
  }

  /**
   * 带 expectedContentHash 的推进打开：hash 不匹配说明调用方持有过期状态，操作不得开始。
   */
  public TimelineWorkingCopy openNext(NodeRef savedRef, String expectedContentHash) {
    return openNext(savedRef, expectedContentHash, null);
  }

  /** A previously loaded immutable state may be reused after checking its hash and validity. */
  public TimelineWorkingCopy openNext(NodeRef savedRef, String expectedContentHash, StaticWorldState preparedState) {
    TimelineNode base = requireSavedNode(savedRef);
    requireExpectedContentHash(base, expectedContentHash);
    NodeRef head = store.head(base.ref().branch())
        .orElseThrow(() -> new TimelineException("TIMELINE_STORE_CORRUPT",
            "分支无 head: " + base.ref().branch()));
    if (!head.equals(base.ref())) {
      throw new TimelineException("TIMELINE_FORK_REQUIRED",
          "历史节点不能原地推进；请从该节点 fork 新分支: node=" + base.ref() + " head=" + head);
    }
    StaticWorldState state = preparedState(base, preparedState);
    return new TimelineWorkingCopy(base, state, TimelineWorkingCopy.Kind.ADVANCE, base.ref().branch());
  }

  /** 从任意已保存节点 fork 一个尚不存在的新分支。 */
  public TimelineWorkingCopy openFork(NodeRef savedRef, BranchId newBranch) {
    return openFork(savedRef, newBranch, null);
  }

  /** 带 expectedContentHash 的 fork 打开。 */
  public TimelineWorkingCopy openFork(NodeRef savedRef, BranchId newBranch, String expectedContentHash) {
    return openFork(savedRef, newBranch, expectedContentHash, null);
  }

  public TimelineWorkingCopy openFork(NodeRef savedRef, BranchId newBranch, String expectedContentHash,
      StaticWorldState preparedState) {
    Objects.requireNonNull(newBranch, "newBranch");
    TimelineNode base = requireSavedNode(savedRef);
    requireExpectedContentHash(base, expectedContentHash);
    if (newBranch.equals(base.ref().branch())) {
      throw new TimelineException("TIMELINE_FORK_SAME_BRANCH",
          "fork 目标分支必须不同于父分支；同分支推进请用 openNext: " + newBranch);
    }
    if (store.branches().contains(newBranch)) {
      throw new TimelineException("TIMELINE_BRANCH_EXISTS", "fork 目标分支已存在: " + newBranch);
    }
    StaticWorldState state = preparedState(base, preparedState);
    return new TimelineWorkingCopy(base, state, TimelineWorkingCopy.Kind.FORK, newBranch);
  }

  /** 追加一次写操作；同一 working copy 的多次调用按顺序合并成一个 ChangeSet。 */
  public TimelineWorkingCopy apply(TimelineWorkingCopy workingCopy, WorldChange change) {
    Objects.requireNonNull(workingCopy, "workingCopy");
    workingCopy.append(change);
    return workingCopy;
  }

  public TimelineWorkingCopy applyAll(TimelineWorkingCopy workingCopy, List<? extends WorldChange> changes) {
    Objects.requireNonNull(workingCopy, "workingCopy");
    Objects.requireNonNull(changes, "changes");
    workingCopy.appendAll(changes);
    return workingCopy;
  }

  /** Validate a candidate before a batch changes the draft. */
  public void validateCandidate(TimelineWorkingCopy copy, StaticWorldState candidate, List<WorldChange> changes) {
    Objects.requireNonNull(copy, "copy");
    if (!copy.isOpen()) throw new TimelineException("TIMELINE_WORKING_COPY_CLOSED", "working copy 已关闭");
    requireValidState(candidate);
    List<WorldChange> all = new java.util.ArrayList<>(copy.pendingChanges());
    all.addAll(changes);
    requireNoNewTopologyIssues(copy.baseState(), candidate, all);
  }

  /** 丢弃内存更改；不会写时间线，也不会移动 head。 */
  public void discard(TimelineWorkingCopy workingCopy) {
    Objects.requireNonNull(workingCopy, "workingCopy").discard();
  }

  /**
   * 保存 working copy 为新节点。推进模式目标分支固定为 base 分支；fork 模式目标分支为 openFork 指定分支。
   * 目标 tick 必须大于父节点 tick。
   */
  public TimelineNode save(TimelineWorkingCopy workingCopy, long targetTick, String savedBy) {
    return save(workingCopy, targetTick, savedBy, null);
  }

  /**
   * 带 expectedContentHash 的保存。expected 是调用方打开 working copy 时看到的 base state hash；
   * 不匹配则零写入，working copy 保持打开。
   */
  public TimelineNode save(TimelineWorkingCopy workingCopy, long targetTick, String savedBy,
      String expectedContentHash) {
    return save(workingCopy, targetTick, savedBy, expectedContentHash, null);
  }

  /** Save a node and its optional idempotency receipt as one store publication. */
  public TimelineNode save(TimelineWorkingCopy workingCopy, long targetTick, String savedBy,
      String expectedContentHash, CommitReceipt receipt) {
    Objects.requireNonNull(workingCopy, "workingCopy");
    requireSavedBy(savedBy);
    requireExpectedContentHash(workingCopy.baseNode(), expectedContentHash);
    if (!workingCopy.isOpen()) {
      throw new TimelineException("TIMELINE_WORKING_COPY_CLOSED",
          "working copy 已保存或已放弃: base=" + workingCopy.baseNode().ref());
    }
    if (targetTick < 0) {
      throw new TimelineException("TIMELINE_TICK_INVALID", "目标 tick 必须 >= 0: " + targetTick);
    }
    if (targetTick <= workingCopy.baseNode().ref().tick()) {
      throw new TimelineException("TIMELINE_TICK_NOT_ADVANCING",
          "目标 tick 必须大于父节点: target=" + targetTick
              + " parent=" + workingCopy.baseNode().ref());
    }

    BranchId targetBranch = workingCopy.targetBranch();
    if (workingCopy.kind() == TimelineWorkingCopy.Kind.ADVANCE) {
      NodeRef head = store.head(targetBranch)
          .orElseThrow(() -> new TimelineException("TIMELINE_STORE_CORRUPT",
              "推进分支无 head: " + targetBranch));
      if (!head.equals(workingCopy.baseNode().ref())) {
        throw new TimelineException("TIMELINE_FORK_REQUIRED",
            "分支 head 已变化，不能从旧节点推进；请 fork: base="
                + workingCopy.baseNode().ref() + " head=" + head);
      }
    } else if (store.branches().contains(targetBranch)) {
      throw new TimelineException("TIMELINE_BRANCH_EXISTS", "fork 目标分支已存在: " + targetBranch);
    }

    StaticWorldState state = workingCopy.currentState();
    requireValidState(state);
    requireNoNewTopologyIssues(workingCopy.baseState(), state, workingCopy.pendingChanges());
    NodeRef target = new NodeRef(targetBranch, targetTick);
    if (receipt != null && !target.equals(receipt.node())) {
      throw new TimelineException("TIMELINE_RECEIPT_INVALID", "提交回执节点与目标不一致: " + target);
    }
    String stateJson = state.canonicalJson();

    List<TimelineNode> existing = store.nodes(targetBranch);
    int nodeNumber = existing.size() + 1;
    boolean checkpoint = existing.isEmpty() || nodeNumber % checkpointEvery == 0;
    TimelineNode node = new TimelineNode(
        target,
        workingCopy.baseNode().ref(),
        new WorldChangeSet(workingCopy.pendingChanges()).canonicalJson(),
        state.contentHash(),
        System.currentTimeMillis(),
        savedBy);
    store.saveNode(node, checkpoint ? stateJson : null, receipt);
    workingCopy.markSaved(target);
    rememberSavedState(node, state);
    return node;
  }

  private synchronized void rememberSavedState(TimelineNode node, StaticWorldState state) {
    if (cachedReplay.states.isEmpty()) return;
    cachedReplay.mapFor(state).validated = true;
    cachedReplay.states.put(node.ref(), new ReplayCache.Entry(state, true));
  }

  /** 在当前节点生成/刷新派生 checkpoint；不是新时间线节点。 */
  public void checkpoint(NodeRef savedRef) {
    TimelineNode node = requireSavedNode(savedRef);
    StaticWorldState state = load(node.ref());
    store.saveCheckpoint(node.ref(), state.canonicalJson());
  }

  private TimelineNode requireSavedNode(NodeRef ref) {
    Objects.requireNonNull(ref, "ref");
    return store.find(ref)
        .orElseThrow(() -> new TimelineException("TIMELINE_NODE_NOT_FOUND",
            "节点未保存或不存在: " + ref));
  }

  private StaticWorldState preparedState(TimelineNode base, StaticWorldState state) {
    if (state == null) return load(base.ref());
    requireHash(base, state, "prepared-state");
    requireValidState(state);
    return state;
  }

  private static void requireSavedBy(String savedBy) {
    if (savedBy == null || savedBy.isBlank()) {
      throw new TimelineException("TIMELINE_SAVED_BY_INVALID", "savedBy 不得为空白");
    }
  }

  private static void requireValidState(StaticWorldState state) {
    requireValidMap(state.map());
    requireValidDomains(state);
  }

  private static void requireValidMap(WorldMap map) {
    List<ValidationIssue> issues = WorldMapValidator.validate(map);
    if (!issues.isEmpty()) {
      ValidationIssue first = issues.get(0);
      throw new TimelineException("TIMELINE_STATE_INVALID",
          "静态状态校验失败: " + issues.size() + " 个问题；首个: "
              + first.code() + " " + first.message());
    }
  }

  private static void requireValidDomains(StaticWorldState state) {
    try {
      state.countries().validate(state.map());
      state.formations().validate(state.map(), state.countries(), state.simulationTime());
    } catch (IllegalArgumentException e) {
      throw new TimelineException("TIMELINE_STATE_INVALID", e.getMessage(), e);
    }
  }

  private static void requireNoNewTopologyIssues(StaticWorldState base, StaticWorldState state,
      List<WorldChange> changes) {
    boolean spatialChange = changes.stream().anyMatch(change -> switch (change) {
      case WorldChange.PutProvince op -> {
        var before = base.map().provinces().get(op.province().id());
        yield before == null || !before.shape().equals(op.province().shape())
            || !before.kind().equals(op.province().kind());
      }
      case WorldChange.PatchProvince op -> op.fields().containsKey("shape") || op.fields().containsKey("kind");
      case WorldChange.RemoveProvince ignored -> true;
      case WorldChange.ReplaceAdjacencies ignored -> true;
      case WorldChange.PutRailway ignored -> true;
      case WorldChange.RemoveRailway ignored -> true;
      default -> false;
    });
    if (!spatialChange) return;
    Set<String> before = PolygonTopology.consistencyIssues(base.map());
    Set<String> after = new TreeSet<>(PolygonTopology.consistencyIssues(state.map()));
    after.removeAll(before);
    if (!after.isEmpty()) {
      throw new TimelineException("TIMELINE_TOPOLOGY_INVALID", "修改引入新的拓扑不一致: " + after.iterator().next());
    }
  }

  private static void requireExpectedContentHash(TimelineNode base, String expectedContentHash) {
    if (expectedContentHash == null) {
      return;
    }
    if (expectedContentHash.isBlank()) {
      throw new TimelineException("TIMELINE_EXPECTED_HASH_INVALID",
          "expectedContentHash 不得为空白: node=" + base.ref());
    }
    if (!expectedContentHash.equals(base.stateHash())) {
      throw new TimelineException("TIMELINE_EXPECTED_HASH_MISMATCH",
          "expectedContentHash 与已保存节点不一致: node=" + base.ref()
              + " expected=" + expectedContentHash + " actual=" + base.stateHash());
    }
  }

  private static void requireHash(TimelineNode node, StaticWorldState state, String stage) {
    requireHash(node, state.contentHash(), stage);
  }

  private static void requireHash(TimelineNode node, String actual, String stage) {
    if (!actual.equals(node.stateHash())) {
      throw new TimelineException("TIMELINE_REPLAY_HASH_MISMATCH",
          "内容 hash 不一致 stage=" + stage + " node=" + node.ref()
              + " expected=" + node.stateHash() + " actual=" + actual);
    }
  }
}
