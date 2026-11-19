package io.mosire.worldsim.app;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.PolygonTopology;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.CommitReceipt;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.timeline.TimelineWorkingCopy;
import io.mosire.worldsim.world.FileTimelineStore;
import io.mosire.worldsim.world.WorldExport;
import io.mosire.worldsim.world.WorldView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Transport-independent GM application service. All world operations share one locked store. */
public final class WorldApplicationService {
  public record SavedMap(NodeRef node, String stateHash, WorldMap map) {}

  private record Applied(String requestHash, Map<String, Object> result) {}
  private record Preview(long version, String commandHash, StaticWorldState candidate, List<WorldChange> changes) {}
  private static final class Draft {
    final String actor;
    final TimelineWorkingCopy copy;
    final Map<String, Applied> requests = new HashMap<>();
    long version;
    Preview preview;
    Draft(String actor, TimelineWorkingCopy copy) { this.actor = actor; this.copy = copy; }
  }

  private final FileTimelineStore store;
  private final TimelineService timeline;
  private final Map<String, Draft> drafts = new HashMap<>();
  private final LinkedHashMap<NodeRef, StaticWorldState> cache = new LinkedHashMap<>(4, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<NodeRef, StaticWorldState> e) { return size() > 2; }
  };

  public WorldApplicationService(FileTimelineStore store) {
    this.store = Objects.requireNonNull(store);
    this.timeline = new TimelineService(store);
  }

  public String worldId() { return store.worldId(); }

  public synchronized SavedMap savedMap(String address) {
    NodeRef ref = address == null ? defaultHead() : node(address);
    TimelineNode saved = requireNode(ref);
    return new SavedMap(ref, saved.stateHash(), load(ref).map());
  }

  public synchronized Map<String, Object> overview(String address) {
    NodeRef ref = address == null ? defaultHead() : node(address);
    TimelineNode saved = requireNode(ref);
    var map = load(ref).map();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("worldId", store.worldId()); result.put("node", ref.label()); result.put("stateHash", saved.stateHash());
    result.put("provinces", map.provinces().size()); result.put("states", map.states().size());
    result.put("regions", map.strategicRegions().size()); result.put("supplyHubs", map.supplyHubs().size());
    result.put("railways", map.railways().size()); result.put("branches", store.branches().size());
    WorldProvenance.Result evidence = WorldProvenance.forNode(store, ref);
    if (evidence.document() != null) {
      result.put("provenanceSource", evidence.document().path("source"));
      result.put("geometryRepairEvidence", evidence.document().path("geometryRepair"));
    }
    if (evidence.warning() != null) result.put("provenanceWarning", evidence.warning());
    return result;
  }

  public synchronized Map<String, Object> lookup(String text, boolean includeGeometry) {
    NodeAddress address = NodeAddress.parse(text);
    StaticWorldState state = load(address.node());
    var resolved = MapAddressResolver.resolve(state.map(), address);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("address", address.canonical());
    result.put("nodeHash", requireNode(address.node()).stateHash());
    result.put("kind", resolved.kind());
    result.put("stableId", resolved.stableId());
    result.put("displayName", resolved.displayName());
    if (resolved.value() instanceof Province p && !includeGeometry) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("id", p.id()); details.put("name", p.name()); details.put("kind", p.kind());
      details.put("terrain", p.terrain()); details.put("displayPoint", p.displayPoint());
      details.put("stateId", p.stateId()); details.put("regionId", p.regionId()); details.put("tags", p.tags());
      details.put("polygonParts", p.shape().parts().size());
      result.put("entity", details);
    } else {
      result.put("entity", resolved.value());
    }
    WorldProvenance.Result evidence = WorldProvenance.forNode(store, address.node());
    if (resolved.value() instanceof Province p) {
      var entries = evidence.provinceEvidence(p.id());
      if (!entries.isEmpty()) result.put("fieldEvidence", entries);
    }
    if (evidence.warning() != null) result.put("provenanceWarning", evidence.warning());
    return result;
  }

  public synchronized Map<String, Object> tree(int offset, int limit) {
    requirePage(offset, limit);
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (BranchId branch : store.branches()) {
      for (TimelineNode node : store.nodes(branch)) nodes.add(nodeSummary(node));
    }
    int from = Math.min(offset, nodes.size());
    int to = Math.min(from + limit, nodes.size());
    return Map.of("total", nodes.size(), "offset", from, "nodes", nodes.subList(from, to));
  }

  public synchronized Map<String, Object> diff(String fromText, String toText) {
    return diff(fromText, toText, 0, 100);
  }

  public synchronized Map<String, Object> diff(String fromText, String toText, int offset, int limit) {
    requirePage(offset, limit);
    NodeRef from = node(fromText), to = node(toText);
    requireNode(from); requireNode(to);
    List<TimelineNode> path = store.pathToRoot(to);
    int at = -1;
    for (int i = 0; i < path.size(); i++) if (path.get(i).ref().equals(from)) at = i;
    if (at < 0) throw new TimelineException("TIMELINE_NOT_ANCESTOR", "from 不是 to 的祖先");
    List<Map<String, Object>> changes = new ArrayList<>();
    for (int i = at + 1; i < path.size(); i++) {
      var n = path.get(i);
      var operations = WorldChangeSet.fromJson(n.changeSetJson()).changes();
      Map<String, Integer> kinds = new TreeMap<>();
      operations.forEach(op -> kinds.merge(op.getClass().getSimpleName(), 1, Integer::sum));
      changes.add(Map.of("node", n.ref().label(), "changeCount", operations.size(), "kinds", kinds));
    }
    int start = Math.min(offset, changes.size());
    return Map.of("from", from.label(), "to", to.label(), "total", changes.size(), "offset", start,
        "nodes", changes.subList(start, Math.min(start + limit, changes.size())));
  }

  public synchronized Map<String, Object> validate(String address) {
    store.verifyStorageIntegrity();
    NodeRef ref = node(address);
    StaticWorldState state = timeline.load(ref);
    var topology = new java.util.TreeSet<>(PolygonTopology.consistencyIssues(state.map()));
    return Map.of("node", ref.label(), "stateHash", state.contentHash(), "topologyIssueCount", topology.size(),
        "topologyIssues", topology.stream().limit(100).toList(), "truncated", topology.size() > 100);
  }

  public synchronized Map<String, Object> verifyTimeline() {
    return Map.of("result", "PASS", "verifiedNodes", timeline.verifyAll());
  }

  public synchronized Map<String, Object> open(String actor, String base, String expectedHash, String forkBranch) {
    requireText(actor, "actor"); requireText(expectedHash, "expectedHash");
    NodeRef ref = node(base);
    StaticWorldState prepared = load(ref);
    TimelineWorkingCopy copy = forkBranch == null
        ? timeline.openNext(ref, expectedHash, prepared)
        : timeline.openFork(ref, new BranchId(requireText(forkBranch, "forkBranch")), expectedHash, prepared);
    String id = UUID.randomUUID().toString();
    drafts.put(id, new Draft(actor, copy));
    return Map.of("draftId", id, "draftVersion", 0, "base", ref.label(), "baseHash", copy.baseNode().stateHash(),
        "targetBranch", copy.targetBranch().value(), "mode", copy.kind().name().toLowerCase());
  }

  public synchronized Map<String, Object> apply(String actor, String draftId, long expectedVersion,
      String requestId, List<Map<String, Object>> commands, boolean dryRun) {
    Draft draft = draft(actor, draftId);
    requireText(requestId, "requestId");
    if (commands == null || commands.isEmpty() || commands.size() > 100) {
      throw new TimelineException("COMMAND_BATCH_INVALID", "commands 数量必须为 1..100");
    }
    String requestHash = JsonCodec.sha256(JsonCodec.canonicalJson(List.of(draftId, expectedVersion, commands, dryRun)));
    Applied previous = draft.requests.get(requestId);
    if (previous != null) {
      if (!previous.requestHash().equals(requestHash)) throw new TimelineException("REQUEST_ID_CONFLICT", "requestId 已用于其他请求");
      return previous.result();
    }
    requireVersion(draft, expectedVersion);
    String commandHash = JsonCodec.sha256(JsonCodec.canonicalJson(commands));
    StaticWorldState candidate;
    List<WorldChange> changes;
    if (draft.preview != null && draft.preview.version() == draft.version
        && draft.preview.commandHash().equals(commandHash)) {
      candidate = draft.preview.candidate();
      changes = draft.preview.changes();
    } else {
      candidate = draft.copy.currentState();
      changes = new ArrayList<>();
      for (Map<String, Object> command : commands) {
        WorldChange change = WorldCommand.parse(command).plan(candidate);
        candidate = new WorldChangeSet(List.of(change)).apply(candidate);
        changes.add(change);
      }
      timeline.validateCandidate(draft.copy, candidate, changes);
    }
    if (!dryRun) {
      timeline.applyAll(draft.copy, changes);
      draft.version++;
      draft.preview = null;
    } else {
      draft.preview = new Preview(draft.version, commandHash, candidate, List.copyOf(changes));
    }
    Map<String, Object> result = Map.of("draftId", draftId, "draftVersion", draft.version,
        "dryRun", dryRun, "appliedCount", changes.size(), "pendingCount",
        draft.copy.pendingChangeCount() + (dryRun ? changes.size() : 0), "stateHash", candidate.contentHash(),
        "changes", changes.stream().map(c -> c.getClass().getSimpleName()).toList());
    draft.requests.put(requestId, new Applied(requestHash, result));
    return result;
  }

  public synchronized Map<String, Object> save(String actor, String draftId, long expectedVersion,
      String requestId, long tick, String baseHash) {
    requireText(actor, "actor"); requireText(requestId, "requestId"); requireText(baseHash, "baseHash");
    String requestHash = JsonCodec.sha256(JsonCodec.canonicalJson(List.of(draftId, expectedVersion, tick, baseHash)));
    var existing = store.receipt(actor, requestId);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(requestHash)) throw new TimelineException("REQUEST_ID_CONFLICT", "requestId 已用于其他请求");
      Draft pending = drafts.get(draftId);
      if (pending != null && pending.actor.equals(actor)) drafts.remove(draftId);
      return Map.of("node", existing.get().node().label(), "stateHash", requireNode(existing.get().node()).stateHash(), "replayedReceipt", true);
    }
    Draft draft = draft(actor, draftId);
    requireVersion(draft, expectedVersion);
    NodeRef ref = new NodeRef(draft.copy.targetBranch(), tick);
    CommitReceipt receipt = new CommitReceipt(actor, requestId, requestHash, ref);
    TimelineNode saved = timeline.save(draft.copy, tick, actor, baseHash, receipt);
    StaticWorldState state = draft.copy.currentState();
    cache.put(saved.ref(), state);
    drafts.remove(draftId);
    Map<String, Object> result = new LinkedHashMap<>(nodeSummary(saved));
    result.put("replayedReceipt", false);
    try {
      result.put("view", WorldView.refresh(store, saved.ref(), state));
      result.put("warnings", List.of());
    } catch (RuntimeException e) {
      result.put("warnings", List.of("节点已保存，可读视图刷新失败: " + e.getMessage()));
    }
    return result;
  }

  public synchronized Map<String, Object> discard(String actor, String draftId, long expectedVersion) {
    Draft draft = draft(actor, draftId);
    requireVersion(draft, expectedVersion);
    timeline.discard(draft.copy);
    drafts.remove(draftId);
    return Map.of("draftId", draftId, "discarded", true);
  }

  public synchronized Map<String, Object> exportWorld() {
    return Map.of("worldId", store.worldId(), "path", WorldExport.export(store), "format", "world-directory-v1");
  }

  public synchronized Map<String, Object> refreshView(String address) {
    NodeRef ref = node(address);
    return Map.of("view", WorldView.refresh(store, ref, load(ref)));
  }

  private Draft draft(String actor, String id) {
    Draft draft = drafts.get(requireText(id, "draftId"));
    if (draft == null || !draft.actor.equals(actor)) throw new TimelineException("DRAFT_NOT_FOUND", "工作副本不存在: " + id);
    return draft;
  }

  private static void requireVersion(Draft draft, long version) {
    if (version != draft.version) throw new TimelineException("DRAFT_VERSION_CONFLICT", "工作副本版本不一致: expected=" + version + " actual=" + draft.version);
  }

  private StaticWorldState load(NodeRef ref) { return cache.computeIfAbsent(ref, timeline::load); }
  private TimelineNode requireNode(NodeRef ref) {
    return store.find(ref).orElseThrow(() -> new TimelineException("TIMELINE_NODE_NOT_FOUND", "节点不存在: " + ref));
  }
  private NodeRef defaultHead() {
    BranchId branch = store.branches().contains(new BranchId("main")) ? new BranchId("main") : store.branches().iterator().next();
    return store.head(branch).orElseThrow();
  }
  private static NodeRef node(String text) {
    NodeAddress address = NodeAddress.parse(text);
    if (!address.path().isEmpty()) throw new TimelineException("TIMELINE_NODE_INVALID", "需要节点地址: " + text);
    return address.node();
  }
  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new TimelineException("ARGUMENT_INVALID", name + " 不得为空");
    return value;
  }
  private static void requirePage(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 200) throw new TimelineException("ARGUMENT_INVALID", "offset 必须非负，limit 必须为 1..200");
  }
  private static Map<String, Object> nodeSummary(TimelineNode node) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("node", node.ref().label()); value.put("parent", node.parent() == null ? null : node.parent().label());
    value.put("stateHash", node.stateHash()); value.put("savedBy", node.savedBy()); value.put("savedAt", node.savedAtEpochMilli());
    return value;
  }
}
