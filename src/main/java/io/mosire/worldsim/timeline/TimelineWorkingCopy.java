package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 内存 working copy。它不是时间线节点，不能作为父节点，也不能被其他分支引用。
 *
 * <p>写入按调用顺序追加到同一个 ChangeSet；没有 undo 栈。只能由 {@link TimelineService} 创建、修改、保存或放弃。
 */
public final class TimelineWorkingCopy {
  public enum Kind {
    ADVANCE,
    FORK
  }

  private final TimelineNode baseNode;
  private final StaticWorldState baseState;
  private final BranchId targetBranch;
  private final Kind kind;
  private final List<WorldChange> pendingChanges = new ArrayList<>();

  private StaticWorldState currentState;
  private boolean open = true;
  private NodeRef savedRef;

  TimelineWorkingCopy(TimelineNode baseNode, StaticWorldState baseState, Kind kind, BranchId targetBranch) {
    this.baseNode = Objects.requireNonNull(baseNode, "baseNode");
    this.baseState = Objects.requireNonNull(baseState, "baseState");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.targetBranch = Objects.requireNonNull(targetBranch, "targetBranch");
    this.currentState = baseState;
  }

  public TimelineNode baseNode() {
    return baseNode;
  }

  public StaticWorldState baseState() {
    return baseState;
  }

  public BranchId targetBranch() {
    return targetBranch;
  }

  public Kind kind() {
    return kind;
  }

  public synchronized StaticWorldState currentState() {
    return currentState;
  }

  public synchronized List<WorldChange> pendingChanges() {
    return List.copyOf(pendingChanges);
  }

  public synchronized int pendingChangeCount() {
    return pendingChanges.size();
  }

  public synchronized boolean isOpen() {
    return open;
  }

  public synchronized Optional<NodeRef> savedRef() {
    return Optional.ofNullable(savedRef);
  }

  synchronized StaticWorldState append(WorldChange change) {
    requireOpen();
    WorldChangeSet one = new WorldChangeSet(List.of(Objects.requireNonNull(change, "change")));
    try {
      currentState = one.apply(currentState);
    } catch (RuntimeException e) {
      throw new TimelineException("TIMELINE_CHANGE_APPLY_FAILED",
          "Change 无法应用到 working copy: " + change, e);
    }
    pendingChanges.add(change);
    return currentState;
  }

  synchronized void discard() {
    requireOpen();
    pendingChanges.clear();
    currentState = baseState;
    open = false;
  }

  synchronized void markSaved(NodeRef ref) {
    requireOpen();
    savedRef = Objects.requireNonNull(ref, "ref");
    open = false;
  }

  private void requireOpen() {
    if (!open) {
      throw new TimelineException("TIMELINE_WORKING_COPY_CLOSED",
          "working copy 已保存或已放弃: base=" + baseNode.ref());
    }
  }
}
