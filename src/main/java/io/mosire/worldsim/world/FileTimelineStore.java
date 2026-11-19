package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.CommitReceipt;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.timeline.TimelineStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;

/** JSON world store. world.json is the sole publication point for nodes and checkpoint references. */
public final class FileTimelineStore implements TimelineStore {
  private final SnapshotFiles.MapCache cachedSnapshotMaps = new SnapshotFiles.MapCache();
  public static final int FORMAT_VERSION = 1;

  public record Manifest(int formatVersion, String worldId, String dataDirectory,
      Map<String, String> heads, Map<String, String> checkpoints) {}

  public record NodeFile(int formatVersion, String branch, long tick, String parentBranch,
      Long parentTick, String parentPath, String changeHash, String stateHash,
      long savedAtEpochMilli, String savedBy, CommitReceipt receipt) {}

  private record Entry(TimelineNode node, String path, String parentPath, CommitReceipt receipt) {}

  private final WorldDirectory world;
  private final Consumer<String> hook;
  private final Map<NodeRef, Entry> entries = new TreeMap<>();
  private final Map<String, Entry> entriesByPath = new HashMap<>();
  private final Map<String, CommitReceipt> receipts = new HashMap<>();
  private Manifest manifest;
  private boolean closed;
  private boolean requiresReopen;

  private FileTimelineStore(WorldDirectory world, Manifest manifest, Consumer<String> hook) {
    this.world = world;
    this.manifest = manifest;
    this.hook = hook;
  }

  public static FileTimelineStore open(Path directory) {
    WorldDirectory world = WorldDirectory.open(directory);
    try {
      Path entry = world.resolve("world.json");
      if (!Files.isRegularFile(entry)) {
        throw new TimelineException("WORLD_NOT_INITIALIZED", "world.json 不存在: " + world.root());
      }
      Manifest manifest = readManifest(world);
      FileTimelineStore store = new FileTimelineStore(world, manifest, ignored -> {});
      store.rebuildIndex();
      return store;
    } catch (RuntimeException e) {
      world.close();
      throw e;
    }
  }

  public static FileTimelineStore create(Path directory, String worldId) {
    return create(directory, worldId, ignored -> {});
  }

  /** Hook is used by fault-injection tests; it is never supplied by the MCP surface. */
  public static FileTimelineStore create(Path directory, String worldId, Consumer<String> hook) {
    if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId 不得为空");
    WorldDirectory world = WorldDirectory.open(directory, true);
    try {
      return createLocked(world, worldId, hook);
    } catch (RuntimeException e) {
      world.close();
      throw e;
    }
  }

  /** Takes ownership of an already held world lock. */
  public static FileTimelineStore createLocked(WorldDirectory world, String worldId) {
    return createLocked(world, worldId, ignored -> {});
  }

  private static FileTimelineStore createLocked(WorldDirectory world, String worldId, Consumer<String> hook) {
    if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId 不得为空");
    if (Files.exists(world.resolve("world.json"))) {
      throw new TimelineException("WORLD_ALREADY_INITIALIZED", "世界已经存在: " + world.root());
    }
    Manifest manifest = new Manifest(FORMAT_VERSION, worldId, "data/" + UUID.randomUUID(), Map.of(), Map.of());
    return new FileTimelineStore(world, manifest, Objects.requireNonNull(hook, "hook"));
  }

  public WorldDirectory world() { ensureOpen(); return world; }
  public String worldId() { ensureOpen(); return manifest.worldId(); }
  public String dataDirectory() { ensureOpen(); return manifest.dataDirectory(); }

  private static Manifest readManifest(WorldDirectory world) {
    try {
      Manifest m = JsonCodec.fromJson(DurableFiles.read(world.resolve("world.json")), Manifest.class);
      if (m.formatVersion() != FORMAT_VERSION) {
        throw new TimelineException("WORLD_FORMAT_UNSUPPORTED", "不支持的世界格式: " + m.formatVersion());
      }
      if (m.worldId() == null || m.worldId().isBlank() || m.dataDirectory() == null
          || !m.dataDirectory().matches("data/[0-9a-fA-F-]{36}") || m.heads() == null
          || m.checkpoints() == null || m.heads().isEmpty()) {
        throw new TimelineException("WORLD_MANIFEST_INVALID", "world.json 不完整");
      }
      world.resolve(m.dataDirectory());
      return m;
    } catch (TimelineException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TimelineException("WORLD_MANIFEST_INVALID", "无法解析 world.json", e);
    }
  }

  private void rebuildIndex() {
    entries.clear();
    entriesByPath.clear();
    receipts.clear();
    for (var head : manifest.heads().entrySet()) {
      BranchId branch = new BranchId(head.getKey());
      Entry entry = visit(head.getValue());
      if (!entry.node().ref().branch().equals(branch)) {
        throw new TimelineException("WORLD_MANIFEST_INVALID", "分支 head 指向其他分支: " + branch);
      }
    }
    long roots = entries.values().stream().filter(e -> e.node().parent() == null).count();
    if (roots != 1) throw new TimelineException("WORLD_TIMELINE_INVALID", "世界应有且只有一个创世节点: " + roots);
    for (BranchId branch : branches()) {
      List<TimelineNode> list = nodes(branch);
      NodeRef head = entriesByPath.get(manifest.heads().get(branch.value())).node().ref();
      if (!list.get(list.size() - 1).ref().equals(head)) {
        throw new TimelineException("WORLD_TIMELINE_INVALID", "分支 head 不完整: " + branch);
      }
      for (int i = 1; i < list.size(); i++) {
        if (!list.get(i - 1).ref().equals(list.get(i).parent())) {
          throw new TimelineException("WORLD_TIMELINE_INVALID", "分支节点链不连续: " + list.get(i).ref());
        }
      }
    }
    for (var checkpoint : manifest.checkpoints().entrySet()) {
      NodeAddress address = NodeAddress.parse(checkpoint.getKey());
      if (!address.path().isEmpty() || !entries.containsKey(address.node())) {
        throw new TimelineException("WORLD_MANIFEST_INVALID", "checkpoint 指向未知节点: " + checkpoint.getKey());
      }
      requireWithinData(checkpoint.getValue(), "snapshots/");
      if (!Files.isRegularFile(world.resolve(checkpoint.getValue()))) {
        throw new TimelineException("WORLD_CHECKPOINT_MISSING", "checkpoint 文件缺失: " + checkpoint.getValue());
      }
    }
    if (genesis().filter(n -> manifest.checkpoints().containsKey(n.ref().label())).isEmpty()) {
      throw new TimelineException("WORLD_CHECKPOINT_MISSING", "创世 checkpoint 缺失");
    }
  }

  private Entry visit(String path) {
    List<Entry> pending = new ArrayList<>();
    Set<String> visiting = new HashSet<>();
    String cursor = path;
    while (cursor != null && !entriesByPath.containsKey(cursor)) {
      requireWithinData(cursor, "timeline/");
      if (!visiting.add(cursor)) throw new TimelineException("WORLD_TIMELINE_INVALID", "节点父链成环: " + cursor);
      Entry entry = readEntry(cursor);
      pending.add(entry);
      cursor = entry.parentPath();
    }
    for (int i = pending.size() - 1; i >= 0; i--) {
      Entry entry = pending.get(i);
      NodeRef ref = entry.node().ref();
      NodeRef parent = entry.node().parent();
      if (parent != null) {
        Entry actualParent = entriesByPath.get(entry.parentPath());
        if (actualParent == null || !parent.equals(actualParent.node().ref()) || ref.tick() <= parent.tick()) {
          throw new TimelineException("WORLD_NODE_INVALID", "节点父引用不一致: " + ref);
        }
      }
      Entry previous = entries.putIfAbsent(ref, entry);
      if (previous != null && !previous.path().equals(entry.path())) {
        throw new TimelineException("WORLD_TIMELINE_INVALID", "节点坐标重复: " + ref);
      }
      entriesByPath.put(entry.path(), entry);
      if (entry.receipt() != null) {
        String key = receiptKey(entry.receipt().actor(), entry.receipt().requestId());
        if (receipts.putIfAbsent(key, entry.receipt()) != null) {
          throw new TimelineException("WORLD_RECEIPT_INVALID", "重复提交回执: " + entry.receipt().requestId());
        }
      }
    }
    return entriesByPath.get(path);
  }

  private Entry readEntry(String path) {
    try {
      NodeFile file = JsonCodec.fromJson(DurableFiles.read(world.resolve(path + "/node.json")), NodeFile.class);
      if (file.formatVersion() != FORMAT_VERSION || file.branch() == null || file.stateHash() == null
          || file.stateHash().isBlank() || file.changeHash() == null || file.savedBy() == null
          || file.savedBy().isBlank()) {
        throw new TimelineException("WORLD_NODE_INVALID", "节点元数据不完整: " + path);
      }
      NodeRef ref = new NodeRef(new BranchId(file.branch()), file.tick());
      boolean hasParent = file.parentPath() != null;
      if (hasParent ? file.parentBranch() == null || file.parentTick() == null
          : file.parentBranch() != null || file.parentTick() != null) {
        throw new TimelineException("WORLD_NODE_INVALID", "节点父引用不完整: " + ref);
      }
      NodeRef parent = hasParent ? new NodeRef(new BranchId(file.parentBranch()), file.parentTick()) : null;
      if (parent == null && ref.tick() != 0) {
        throw new TimelineException("WORLD_NODE_INVALID", "创世 tick 必须为 0: " + ref);
      }
      String changeText = DurableFiles.read(world.resolve(path + "/changes.json"));
      if (!JsonCodec.sha256(changeText).equals(file.changeHash())) {
        throw new TimelineException("WORLD_CHANGE_HASH_MISMATCH", "节点增量文件 hash 不一致: " + ref);
      }
      String canonical = WorldChangeSet.fromJson(changeText).canonicalJson();
      TimelineNode node = new TimelineNode(ref, parent, canonical, file.stateHash(), file.savedAtEpochMilli(), file.savedBy());
      if (file.receipt() != null && !file.receipt().node().equals(ref)) {
        throw new TimelineException("WORLD_RECEIPT_INVALID", "节点回执指向其他节点: " + ref);
      }
      return new Entry(node, path, file.parentPath(), file.receipt());
    } catch (TimelineException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TimelineException("WORLD_NODE_INVALID", "无法读取节点: " + path, e);
    }
  }

  @Override public synchronized void initialize() { ensureOpen(); }
  @Override public synchronized boolean isEmpty() { ensureOpen(); return entries.isEmpty(); }
  @Override public synchronized Optional<TimelineNode> genesis() {
    ensureOpen();
    return entries.values().stream().map(Entry::node).filter(n -> n.parent() == null).findFirst();
  }
  @Override public synchronized Optional<TimelineNode> find(NodeRef ref) {
    ensureOpen();
    return Optional.ofNullable(entries.get(Objects.requireNonNull(ref))).map(Entry::node);
  }
  @Override public synchronized boolean exists(NodeRef ref) { return find(ref).isPresent(); }

  @Override public synchronized List<TimelineNode> pathToRoot(NodeRef ref) {
    ensureOpen();
    Entry entry = entries.get(ref);
    if (entry == null) return List.of();
    List<TimelineNode> reversed = new ArrayList<>();
    Set<NodeRef> seen = new HashSet<>();
    while (entry != null) {
      if (!seen.add(entry.node().ref())) throw new TimelineException("WORLD_TIMELINE_INVALID", "节点父链成环: " + ref);
      reversed.add(entry.node());
      entry = entry.node().parent() == null ? null : entries.get(entry.node().parent());
      if (entry == null && reversed.get(reversed.size() - 1).parent() != null) {
        throw new TimelineException("WORLD_TIMELINE_INVALID", "节点父链缺失: " + ref);
      }
    }
    Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  @Override public synchronized List<TimelineNode> nodes(BranchId branch) {
    ensureOpen();
    return entries.values().stream().map(Entry::node).filter(n -> n.ref().branch().equals(branch))
        .sorted((a, b) -> Long.compare(a.ref().tick(), b.ref().tick())).toList();
  }

  @Override public synchronized Set<BranchId> branches() {
    ensureOpen();
    LinkedHashSet<BranchId> result = new LinkedHashSet<>();
    manifest.heads().keySet().stream().sorted().map(BranchId::new).forEach(result::add);
    return Collections.unmodifiableSet(result);
  }

  @Override public synchronized Optional<NodeRef> head(BranchId branch) {
    ensureOpen();
    String path = manifest.heads().get(branch.value());
    return path == null ? Optional.empty() : Optional.of(entriesByPath.get(path).node().ref());
  }

  @Override public synchronized void saveNode(TimelineNode node, String checkpointJson) {
    saveNode(node, checkpointJson, null);
  }

  @Override public synchronized void saveNode(TimelineNode node, String checkpointJson, CommitReceipt receipt) {
    ensureOpen();
    Objects.requireNonNull(node, "node");
    if (entries.containsKey(node.ref())) throw new TimelineException("TIMELINE_NODE_EXISTS", "节点已存在: " + node.ref());
    if (receipt != null) {
      if (!node.ref().equals(receipt.node())) throw new TimelineException("WORLD_RECEIPT_INVALID", "回执节点不一致");
      if (receipts.containsKey(receiptKey(receipt.actor(), receipt.requestId()))) {
        throw new TimelineException("WORLD_REQUEST_EXISTS", "请求号已提交: " + receipt.requestId());
      }
    }
    Entry parent = node.parent() == null ? null : entries.get(node.parent());
    if (node.parent() == null) {
      if (!entries.isEmpty() || node.ref().tick() != 0) throw new TimelineException("TIMELINE_GENESIS_ALREADY_EXISTS", "创世节点无效");
      if (checkpointJson == null) throw new TimelineException("WORLD_CHECKPOINT_MISSING", "创世必须有完整快照");
    } else {
      if (parent == null) throw new TimelineException("TIMELINE_PARENT_NOT_SAVED", "父节点未保存: " + node.parent());
      if (node.ref().tick() <= node.parent().tick()) throw new TimelineException("TIMELINE_TICK_NOT_ADVANCING", "子节点 tick 必须推进");
      Optional<NodeRef> oldHead = head(node.ref().branch());
      if (oldHead.isPresent() && !oldHead.get().equals(node.parent())) {
        throw new TimelineException("TIMELINE_PARENT_NOT_HEAD", "同分支父节点不是 head: " + node.parent());
      }
      if (oldHead.isEmpty() && node.ref().branch().equals(node.parent().branch())) {
        throw new TimelineException("TIMELINE_PARENT_BRANCH_EMPTY", "同分支没有 head");
      }
    }
    String checkpointPath = null;
    if (checkpointJson != null) {
      StaticWorldState state = StaticWorldState.fromJson(checkpointJson);
      if (!state.id().equals(manifest.worldId()) || !state.contentHash().equals(node.stateHash())) {
        throw new TimelineException("WORLD_CHECKPOINT_HASH_MISMATCH", "checkpoint 与节点/world 不一致: " + node.ref());
      }
      checkpointPath = new SnapshotFiles(world, manifest.dataDirectory()).write(state);
    }
    hook.accept("objects-written");
    String id = UUID.randomUUID().toString();
    String branchName = node.ref().branch().value();
    String branch = branchName.matches("[A-Za-z0-9_-]{1,80}")
        && !branchName.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")
        ? branchName : "branch-" + JsonCodec.sha256(branchName).substring("sha256:".length());
    String staging = manifest.dataDirectory() + "/.staging/nodes/" + id;
    String destination = manifest.dataDirectory() + "/timeline/" + branch + "/" + node.ref().tick() + "-" + id;
    String changes;
    try {
      changes = JsonCodec.prettyJson(JsonCodec.mapper().readTree(node.changeSetJson()));
    } catch (java.io.IOException e) {
      throw new TimelineException("WORLD_CHANGE_INVALID", "无法解析节点增量: " + node.ref(), e);
    }
    NodeFile file = new NodeFile(FORMAT_VERSION, node.ref().branch().value(), node.ref().tick(),
        node.parent() == null ? null : node.parent().branch().value(),
        node.parent() == null ? null : node.parent().tick(), parent == null ? null : parent.path(),
        JsonCodec.sha256(changes), node.stateHash(), node.savedAtEpochMilli(), node.savedBy(), receipt);
    DurableFiles.writeNew(world.resolve(staging + "/changes.json"), changes);
    DurableFiles.writeNew(world.resolve(staging + "/node.json"), JsonCodec.prettyJson(file));
    DurableFiles.moveAtomic(world.resolve(staging), world.resolve(destination), false);
    hook.accept("node-published");
    Map<String, String> heads = new TreeMap<>(manifest.heads());
    heads.put(node.ref().branch().value(), destination);
    Map<String, String> checkpoints = new TreeMap<>(manifest.checkpoints());
    if (checkpointPath != null) checkpoints.put(node.ref().label(), checkpointPath);
    Manifest next = new Manifest(FORMAT_VERSION, manifest.worldId(), manifest.dataDirectory(), heads, checkpoints);
    publish(next);
    manifest = next;
    Entry entry = new Entry(node, destination, parent == null ? null : parent.path(), receipt);
    entries.put(node.ref(), entry);
    entriesByPath.put(destination, entry);
    if (receipt != null) receipts.put(receiptKey(receipt.actor(), receipt.requestId()), receipt);
    hook.accept("manifest-published");
  }

  @Override public synchronized void saveCheckpoint(NodeRef ref, String checkpointJson) {
    ensureOpen();
    TimelineNode node = find(ref).orElseThrow(() -> new TimelineException("TIMELINE_NODE_NOT_FOUND", "节点不存在: " + ref));
    StaticWorldState state = StaticWorldState.fromJson(checkpointJson);
    if (!state.id().equals(manifest.worldId()) || !state.contentHash().equals(node.stateHash())) {
      throw new TimelineException("WORLD_CHECKPOINT_HASH_MISMATCH", "checkpoint 与节点不一致: " + ref);
    }
    String path = new SnapshotFiles(world, manifest.dataDirectory()).write(state);
    Map<String, String> checkpoints = new TreeMap<>(manifest.checkpoints());
    checkpoints.put(ref.label(), path);
    Manifest next = new Manifest(FORMAT_VERSION, manifest.worldId(), manifest.dataDirectory(), manifest.heads(), checkpoints);
    publish(next);
    manifest = next;
  }

  @Override public synchronized Optional<String> checkpoint(NodeRef ref) {
    return checkpointState(ref, null).map(StaticWorldState::canonicalJson);
  }

  @Override public synchronized Optional<StaticWorldState> checkpointState(
      NodeRef ref, Function<StaticWorldState, String> cachedHash) {
    ensureOpen();
    String path = manifest.checkpoints().get(ref.label());
    if (path == null) return Optional.empty();
    var snapshots = new SnapshotFiles(world, manifest.dataDirectory());
    StaticWorldState state = cachedHash == null ? snapshots.read(path) : snapshots.read(path, cachedSnapshotMaps, cachedHash);
    if (!state.id().equals(manifest.worldId())) throw new TimelineException("WORLD_CHECKPOINT_HASH_MISMATCH", "快照 worldId 不一致");
    return Optional.of(state);
  }

  @Override public synchronized Optional<CommitReceipt> receipt(String actor, String requestId) {
    ensureOpen();
    return Optional.ofNullable(receipts.get(receiptKey(actor, requestId)));
  }

  @Override public synchronized void verifyStorageIntegrity() {
    ensureOpen();
    Manifest disk = readManifest(world);
    if (!disk.equals(manifest)) {
      requiresReopen = true;
      throw new TimelineException("WORLD_EXTERNAL_MODIFICATION", "运行中的 world.json 已改变，请重启确认");
    }
    try {
      rebuildIndex();
    } catch (RuntimeException e) {
      requiresReopen = true;
      throw e;
    }
  }

  /** Files reachable from committed heads; unfinished staging and orphan nodes are excluded. */
  public synchronized Set<String> committedFiles() {
    ensureOpen();
    Set<String> files = new LinkedHashSet<>();
    files.add("world.json");
    for (Entry entry : entries.values()) {
      files.add(entry.path() + "/node.json");
      files.add(entry.path() + "/changes.json");
    }
    SnapshotFiles snapshots = new SnapshotFiles(world, manifest.dataDirectory());
    for (String path : manifest.checkpoints().values()) {
      files.add(path);
      SnapshotFiles.Manifest snapshot = snapshots.readManifest(path);
      snapshot.chunks().values().forEach(files::addAll);
    }
    return Collections.unmodifiableSet(files);
  }

  private static String receiptKey(String actor, String requestId) {
    return JsonCodec.canonicalJson(List.of(actor, requestId));
  }

  private void publish(Manifest next) {
    String temp = manifest.dataDirectory() + "/.staging/manifests/" + UUID.randomUUID() + ".json";
    DurableFiles.writeNew(world.resolve(temp), JsonCodec.prettyJson(next));
    try {
      DurableFiles.moveAtomic(world.resolve(temp), world.resolve("world.json"), true);
    } catch (RuntimeException failure) {
      // A directory-sync failure may happen after rename: do not keep an out-of-date in-memory head.
      try {
        if (Files.isRegularFile(world.resolve("world.json"))) {
          Manifest published = readManifest(world);
          if (published.equals(next)) {
            manifest = published;
            rebuildIndex();
            throw new TimelineException("WORLD_COMMIT_UNCERTAIN",
                "提交入口已更新但持久化确认失败；请使用相同 requestId 重试", failure);
          }
        }
      } catch (TimelineException e) {
        if (e.code().equals("WORLD_COMMIT_UNCERTAIN")) throw e;
        failure.addSuppressed(e);
        requiresReopen = true;
        throw new TimelineException("WORLD_REOPEN_REQUIRED", "无法确认提交状态，请重启并重试", failure);
      }
      throw failure;
    }
  }

  private void requireWithinData(String relative, String subdir) {
    if (relative == null || !relative.startsWith(manifest.dataDirectory() + "/" + subdir)) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "节点引用不在预期目录: " + relative);
    }
    Path expected = world.resolve(manifest.dataDirectory() + "/" + subdir);
    if (!world.resolve(relative).startsWith(expected)) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "节点引用越界: " + relative);
    }
  }

  private void ensureOpen() {
    if (closed) throw new TimelineException("TIMELINE_STORE_CLOSED", "文件时间线已关闭");
    if (requiresReopen) throw new TimelineException("WORLD_REOPEN_REQUIRED", "世界状态待重启确认");
  }

  @Override public synchronized void close() {
    if (closed) return;
    closed = true;
    world.close();
  }
}
