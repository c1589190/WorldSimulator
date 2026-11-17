package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.map.ValidationIssue;
import io.mosire.worldsim.map.WorldMapValidator;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    TimelineNode target = requireSavedNode(ref);
    List<TimelineNode> path = store.pathToRoot(target.ref());
    if (path.isEmpty()) {
      throw new TimelineException("TIMELINE_STORE_CORRUPT",
          "已找到节点但无法构造根路径: " + target.ref());
    }

    int checkpointIndex = -1;
    StaticWorldState state = null;
    for (int index = path.size() - 1; index >= 0; index--) {
      TimelineNode candidate = path.get(index);
      Optional<String> checkpointJson = store.checkpoint(candidate.ref());
      if (checkpointJson.isEmpty()) {
        continue;
      }
      try {
        state = StaticWorldState.fromJson(checkpointJson.get());
      } catch (RuntimeException e) {
        throw new TimelineException("TIMELINE_CHECKPOINT_INVALID",
            "checkpoint JSON 无法解析: " + candidate.ref(), e);
      }
      requireHash(candidate, state, "checkpoint");
      checkpointIndex = index;
      break;
    }
    if (checkpointIndex < 0) {
      throw new TimelineException("TIMELINE_CHECKPOINT_MISSING",
          "节点路径上没有 checkpoint，无法重建状态: " + target.ref());
    }

    for (int index = checkpointIndex + 1; index < path.size(); index++) {
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
      requireHash(node, state, "replay");
    }
    return state;
  }

  /** 显式校验某节点重放结果；当前与 {@link #load(NodeRef)} 同义。 */
  public StaticWorldState replay(NodeRef ref) {
    return load(ref);
  }

  /** 逐节点重放，返回验证节点数；用于 CLI/回归检查。 */
  public int verifyAll() {
    int verified = 0;
    for (BranchId branch : store.branches()) {
      for (TimelineNode node : store.nodes(branch)) {
        load(node.ref());
        verified++;
      }
    }
    return verified;
  }

  /** 从分支 head 打开推进用 working copy。历史/非 head 节点只能走 {@link #openFork}。 */
  public TimelineWorkingCopy openNext(NodeRef savedRef) {
    TimelineNode base = requireSavedNode(savedRef);
    NodeRef head = store.head(base.ref().branch())
        .orElseThrow(() -> new TimelineException("TIMELINE_STORE_CORRUPT",
            "分支无 head: " + base.ref().branch()));
    if (!head.equals(base.ref())) {
      throw new TimelineException("TIMELINE_FORK_REQUIRED",
          "历史节点不能原地推进；请从该节点 fork 新分支: node=" + base.ref() + " head=" + head);
    }
    StaticWorldState state = load(base.ref());
    return new TimelineWorkingCopy(base, state, TimelineWorkingCopy.Kind.ADVANCE, base.ref().branch());
  }

  /** 从任意已保存节点 fork 一个尚不存在的新分支。 */
  public TimelineWorkingCopy openFork(NodeRef savedRef, BranchId newBranch) {
    Objects.requireNonNull(newBranch, "newBranch");
    TimelineNode base = requireSavedNode(savedRef);
    if (newBranch.equals(base.ref().branch())) {
      throw new TimelineException("TIMELINE_FORK_SAME_BRANCH",
          "fork 目标分支必须不同于父分支；同分支推进请用 openNext: " + newBranch);
    }
    if (store.branches().contains(newBranch)) {
      throw new TimelineException("TIMELINE_BRANCH_EXISTS", "fork 目标分支已存在: " + newBranch);
    }
    StaticWorldState state = load(base.ref());
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
    for (WorldChange change : changes) {
      workingCopy.append(change);
    }
    return workingCopy;
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
    Objects.requireNonNull(workingCopy, "workingCopy");
    requireSavedBy(savedBy);
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
    NodeRef target = new NodeRef(targetBranch, targetTick);
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
    store.saveNode(node, checkpoint ? stateJson : null);
    workingCopy.markSaved(target);
    return node;
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

  private static void requireSavedBy(String savedBy) {
    if (savedBy == null || savedBy.isBlank()) {
      throw new TimelineException("TIMELINE_SAVED_BY_INVALID", "savedBy 不得为空白");
    }
  }

  private static void requireValidState(StaticWorldState state) {
    List<ValidationIssue> issues = WorldMapValidator.validate(state.map());
    if (!issues.isEmpty()) {
      ValidationIssue first = issues.get(0);
      throw new TimelineException("TIMELINE_STATE_INVALID",
          "静态状态校验失败: " + issues.size() + " 个问题；首个: "
              + first.code() + " " + first.message(), null);
    }
  }

  private static void requireHash(TimelineNode node, StaticWorldState state, String stage) {
    String actual = state.contentHash();
    if (!actual.equals(node.stateHash())) {
      throw new TimelineException("TIMELINE_REPLAY_HASH_MISMATCH",
          "内容 hash 不一致 stage=" + stage + " node=" + node.ref()
              + " expected=" + node.stateHash() + " actual=" + actual);
    }
  }
}
