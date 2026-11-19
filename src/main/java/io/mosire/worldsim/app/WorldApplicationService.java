package io.mosire.worldsim.app;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.PolygonTopology;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.orbat.MilOrgFormat;
import io.mosire.worldsim.orbat.Battle;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.CommitReceipt;
import io.mosire.worldsim.timeline.SimulationTime;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.timeline.TimelineWorkingCopy;
import io.mosire.worldsim.world.FileTimelineStore;
import io.mosire.worldsim.world.WorldExport;
import io.mosire.worldsim.world.WorldView;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Transport-independent GM application service. All world operations share one locked store. */
public final class WorldApplicationService {
  public record SavedMap(NodeRef node, String stateHash, StaticWorldState state) {
    public WorldMap map() { return state.map(); }
  }
  public record Clock(String time, String precision, String source) {}

  private record CalendarAnchor(String node, String stateHash, String date, String source) {}
  private record ScenarioCalendar(int formatVersion, List<CalendarAnchor> anchors) {}
  private record Applied(String requestHash, Map<String, Object> result) {}
  private record Preview(long version, String commandHash, StaticWorldState candidate, List<WorldChange> changes) {}
  private record GapScope(NodeRef node, boolean referenceOnly) {}
  private record TerritoryGap(String provinceId, String stableId, String address, String name, String stateId,
                              String stateName, String expectedCountryId) {}
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
  private final Map<NodeRef, Clock> clocks = new HashMap<>();
  private Map<NodeRef, Clock> calendar;
  private WorldGeography geography;
  private final Map<String, Draft> drafts = new HashMap<>();
  private final LinkedHashMap<NodeRef, WorldSearchIndex> search = new LinkedHashMap<>(4, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<NodeRef, WorldSearchIndex> e) { return size() > 2; }
  };
  private final LinkedHashMap<GapScope, Set<String>> territoryGaps = new LinkedHashMap<>(4, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<GapScope, Set<String>> e) { return size() > 4; }
  };

  public WorldApplicationService(FileTimelineStore store) {
    this.store = Objects.requireNonNull(store);
    this.timeline = new TimelineService(store);
  }

  public String worldId() { return store.worldId(); }

  public synchronized SavedMap savedMap(String address) {
    NodeRef ref = address == null ? defaultHead() : node(address);
    TimelineNode saved = requireNode(ref);
    return new SavedMap(ref, saved.stateHash(), load(ref));
  }

  public synchronized Map<String, Object> overview(String address) {
    NodeRef ref = address == null ? defaultHead() : node(address);
    TimelineNode saved = requireNode(ref);
    var state = load(ref);
    var map = state.map();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("worldId", store.worldId()); result.put("node", ref.label()); result.put("stateHash", saved.stateHash());
    result.put("provinces", map.provinces().size()); result.put("states", map.states().size());
    result.put("regions", map.strategicRegions().size()); result.put("supplyHubs", map.supplyHubs().size());
    result.put("railways", map.railways().size()); result.put("branches", store.branches().size());
    result.put("countries", state.countries().countries().size());
    result.put("units", state.formations().units().size());
    putClock(result, clock(ref));
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
    String hash = requireNode(address.node()).stateHash();
    if (!address.requireEntityPath().path().getFirst().equals("map")) {
      return WorldQueries.lookup(state, address, hash);
    }
    var resolved = MapAddressResolver.resolve(state.map(), address);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("address", address.canonical());
    result.put("nodeHash", hash);
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
      var territory = state.countries().territories().get(p.id());
      if (territory != null) result.put("territory", territory);
      var units = WorldQueries.unitMarkers(state, address.node(), p.id());
      if (!units.isEmpty()) result.put("units", units);
      var entries = evidence.provinceEvidence(p.id());
      if (!entries.isEmpty()) result.put("fieldEvidence", entries);
    }
    if (evidence.warning() != null) result.put("provenanceWarning", evidence.warning());
    return result;
  }

  public synchronized Map<String, Object> search(String address, String query, String kind, int offset, int limit) {
    requirePage(offset, limit);
    requireText(query, "query");
    if (kind != null && !WorldSearchIndex.KINDS.contains(kind)) {
      throw new IllegalArgumentException("未知的搜索类别: " + kind);
    }
    SavedMap saved = savedMap(address);
    var index = search.computeIfAbsent(saved.node(), ignored -> new WorldSearchIndex(saved.state()));
    return index.search(saved.node(), saved.stateHash(), query, kind, offset, limit);
  }

  public boolean hasGeographicReference() {
    return Files.isRegularFile(store.world().resolve("geo-reference.json"));
  }

  public synchronized RiverReference riverReference(SavedMap saved) {
    var source = geography(saved);
    return source == null ? null : source.reference().rivers();
  }

  public synchronized Map<String, Object> geoLookup(String nodeLabel, String name, Double longitude, Double latitude,
                                                     String provinceAddress) {
    if (provinceAddress != null) {
      if (nodeLabel != null || name != null || longitude != null || latitude != null) {
        throw new IllegalArgumentException("请单独提供完整省份地址");
      }
      var address = NodeAddress.parse(provinceAddress);
      SavedMap saved = savedMap(address.node().label());
      if (!(MapAddressResolver.resolve(saved.map(), address).value() instanceof Province province)) {
        throw new IllegalArgumentException("请输入省份地址");
      }
      return geoPosition(saved, province);
    }
    if (name == null) {
      if (longitude == null || latitude == null) throw new IllegalArgumentException("请提供地名或经纬度两值");
      WorldGeography.requireCoordinates(longitude, latitude);
    } else {
      requireText(name, "name");
      if (longitude != null || latitude != null) throw new IllegalArgumentException("地名和经纬度须分别查询");
    }
    SavedMap saved = savedMap(nodeLabel);
    var source = geography(saved);
    return source == null
        ? Map.of("node", saved.node().label(), "stateHash", saved.stateHash(), "status", "unavailable", "candidates", List.of())
        : source.lookup(saved, name, longitude, latitude);
  }

  public synchronized Map<String, Object> geoPosition(SavedMap saved, Province province) {
    var source = geography(saved);
    return source == null ? Map.of("node", saved.node().label(), "stateHash", saved.stateHash(), "status", "unavailable",
        "address", WorldQueries.address(saved.node(), "map", "province", province.id())) : source.locate(saved, province);
  }

  public synchronized Map<String, Object> geoOverlay(String address) {
    SavedMap saved = savedMap(address);
    var source = geography(saved);
    return source == null ? Map.of("borders", List.of()) : source.overlay(saved);
  }

  public synchronized Map<String, Object> territoryGaps(String nodeLabel, String scope, String countryId,
                                                         String stateId, int offset, int limit) {
    requirePage(offset, limit);
    if (scope != null && !Set.of("reference", "all").contains(scope)) {
      throw new IllegalArgumentException("scope 须为 reference 或 all");
    }
    SavedMap saved = savedMap(nodeLabel);
    var reference = territoryReference(saved);
    boolean referenceOnly = reference != null && !"all".equals(scope);
    Map<String, String> expected = reference == null ? Map.of() : reference.owners();
    var matches = territoryGapIds(saved, referenceOnly ? reference : null).stream()
        .filter(id -> countryId == null || countryId.equals(expected.get(id)))
        .filter(id -> stateId == null || stateId.equals(saved.map().provinces().get(id).stateId()))
        .sorted().toList();
    int from = Math.min(offset, matches.size());
    int to = Math.min(from + limit, matches.size());
    var page = matches.subList(from, to).stream().map(id -> {
      var province = saved.map().provinces().get(id);
      var state = province.stateId() == null ? null : saved.map().states().get(province.stateId());
      return new TerritoryGap(id, "province:" + id, WorldQueries.address(saved.node(), "map", "province", id),
          province.name(), province.stateId(), state == null ? null : state.name(), expected.get(id));
    }).toList();
    var result = new LinkedHashMap<String, Object>(Map.of("node", saved.node().label(), "stateHash", saved.stateHash(),
        "scope", referenceOnly ? "reference" : "all", "offset", from, "total", matches.size(), "gaps", page));
    if (reference != null) {
      result.put("referenceDate", reference.date());
      result.put("source", reference.source());
    }
    return result;
  }

  public synchronized Set<String> territoryGapIds(SavedMap saved) {
    return territoryGapIds(saved, territoryReference(saved));
  }

  private Set<String> territoryGapIds(SavedMap saved, WorldGeography.TerritoryReference reference) {
    return territoryGaps.computeIfAbsent(new GapScope(saved.node(), reference != null), ignored ->
        saved.map().provinces().values().stream().filter(province -> province.kind().equals("land"))
            .filter(province -> reference == null || reference.owners().containsKey(province.id()))
            .filter(province -> {
              var territory = saved.state().countries().territories().get(province.id());
              return territory == null || territory.owner() == null;
            })
            .map(Province::id).collect(Collectors.toUnmodifiableSet()));
  }

  private WorldGeography.TerritoryReference territoryReference(SavedMap saved) {
    var source = geography(saved);
    return source == null ? null : source.reference().territory();
  }

  private WorldGeography geography(SavedMap saved) {
    if (geography == null) {
      if (!hasGeographicReference()) return null;
      try {
        var parsed = new WorldGeography(Files.readString(store.world().resolve("geo-reference.json")));
        var reference = parsed.reference();
        if (!requireNode(node(reference.base())).stateHash().equals(reference.baseHash())) {
          throw new IllegalArgumentException("地理参考与基准节点的 hash 不一致");
        }
        geography = parsed;
      } catch (IOException | RuntimeException e) {
        throw new TimelineException("GEO_REFERENCE_INVALID", "无法使用世界地理参考: " + e.getMessage());
      }
    }
    var reference = geography.reference();
    if (reference.width() != saved.map().width() || reference.height() != saved.map().height()) {
      throw new TimelineException("GEO_REFERENCE_INVALID", "地理参考与当前地图的尺寸不一致");
    }
    return geography;
  }

  public synchronized List<?> unitMarkers(String address) {
    SavedMap saved = savedMap(address);
    return WorldQueries.unitMarkers(saved.state(), saved.node(), null);
  }

  synchronized WorldMovement.Estimate movementEstimate(String text, String role, List<String> route) {
    var address = NodeAddress.parse(text);
    var saved = savedMap(address.node().label());
    return WorldMovement.estimate(saved, address, role == null ? "actual" : role, route,
        route == null ? null : geography(saved));
  }

  synchronized Map<String, Object> supplyStatus(String text) {
    var address = NodeAddress.parse(text);
    var saved = savedMap(address.node().label());
    var unit = WorldQueries.unit(saved.state(), address);
    var rules = new LandMarchRules(saved.state(), unit.countryId());
    var result = new LinkedHashMap<String, Object>(Map.of("address", address.canonical(), "stateHash", saved.stateHash(),
        "rule", LandMarchRules.VERSION, "supply", rules.supply(unit.provinceId())));
    if (unit.provinceId() != null) result.put("provinceAddress", WorldQueries.address(saved.node(), "map", "province", unit.provinceId()));
    return result;
  }
  public synchronized Map<String, Object> marches(SavedMap saved) {
    var result = new LinkedHashMap<String, Object>(Map.of("node", saved.node().label(), "stateHash", saved.stateHash(),
        "marches", WorldQueries.marches(saved.state(), saved.node())));
    putClock(result, clock(saved.node()));
    return result;
  }

  public synchronized Map<String, Object> battles(SavedMap saved, int offset, int limit) {
    requirePage(offset, limit);
    var records = saved.state().formations().battles().values();
    var entries = records.stream().sorted(Comparator.comparing(Battle::endedAt).reversed().thenComparing(Battle::id))
        .skip(offset).limit(limit)
        .map(battle -> WorldQueries.battle(saved.state(), saved.node(), saved.stateHash(), battle)).toList();
    return Map.of("node", saved.node().label(), "stateHash", saved.stateHash(),
        "total", records.size(), "offset", Math.min(offset, records.size()), "battles", entries);
  }

  public synchronized List<?> battleMarkers(SavedMap saved) {
    if (saved.state().formations().battles().isEmpty()) return List.of();
    var changes = WorldChangeSet.fromJson(requireNode(saved.node()).changeSetJson());
    return changes.changes().stream()
        .filter(WorldChange.ResolveBattle.class::isInstance)
        .map(change -> ((WorldChange.ResolveBattle) change).battle())
        .map(battle -> WorldQueries.battleMarker(saved.state(), saved.node(), battle))
        .filter(Objects::nonNull)
        .toList();
  }

  public synchronized Clock clock(SavedMap saved) {
    return clock(saved.node());
  }

  public synchronized Map<String, Object> lineage(String branchName) {
    BranchId branch = branchName == null ? defaultHead().branch() : new BranchId(branchName);
    NodeRef head = store.head(branch).orElseThrow(() -> new TimelineException("TIMELINE_BRANCH_NOT_FOUND", "分支不存在: " + branch.value()));
    return Map.of("branch", branch.value(), "head", head.label(),
        "branches", store.branches().stream().sorted().map(BranchId::value).toList(),
        "nodes", store.pathToRoot(head).stream().map(this::nodeSummary).toList());
  }

  public synchronized Map<String, Object> exportMilOrg(String text) {
    NodeAddress address = NodeAddress.parse(text);
    var formation = WorldQueries.formation(load(address.node()), address);
    var document = formation.document();
    return MilOrgFormat.write(document, formation.selected() == null ? document.root() : formation.selected());
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
    var result = new LinkedHashMap<String, Object>(Map.of("draftId", id, "draftVersion", 0, "base", ref.label(),
        "baseHash", copy.baseNode().stateHash(), "targetBranch", copy.targetBranch().value(),
        "mode", copy.kind().name().toLowerCase()));
    putClock(result, clock(ref));
    return result;
  }

  public synchronized Map<String, Object> apply(String actor, String draftId, long expectedVersion,
      String requestId, List<Map<String, Object>> commands, boolean dryRun) {
    Draft draft = draft(actor, draftId);
    requireText(requestId, "requestId");
    if (commands == null || commands.isEmpty() || commands.size() > 100) {
      throw new TimelineException("COMMAND_BATCH_INVALID", "commands 数量必须为 1..100");
    }
    String commandHash = JsonCodec.sha256(JsonCodec.canonicalInputJson(commands));
    String requestHash = JsonCodec.sha256(JsonCodec.canonicalJson(List.of(draftId, expectedVersion, commandHash, dryRun)));
    Applied previous = draft.requests.get(requestId);
    if (previous != null) {
      if (!previous.requestHash().equals(requestHash)) throw new TimelineException("REQUEST_ID_CONFLICT", "requestId 已用于其他请求");
      return previous.result();
    }
    requireVersion(draft, expectedVersion);
    StaticWorldState candidate;
    StaticWorldState before = draft.copy.currentState();
    List<WorldChange> changes;
    if (draft.preview != null && draft.preview.version() == draft.version
        && draft.preview.commandHash().equals(commandHash)) {
      candidate = draft.preview.candidate();
      changes = draft.preview.changes();
    } else {
      candidate = before;
      changes = new ArrayList<>();
      var base = draft.copy.baseNode();
      var context = new WorldCommand.Context(base.ref(), base.stateHash(), () -> {
        Clock stamp = clock(base.ref());
        return stamp == null ? null : stamp.time();
      }, this::geography);
      for (Map<String, Object> command : commands) {
        WorldChange change = WorldCommand.parse(command).plan(candidate, context);
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
    Map<String, Object> result = new LinkedHashMap<>(Map.of("draftId", draftId, "draftVersion", draft.version,
        "dryRun", dryRun, "appliedCount", changes.size(), "pendingCount",
        draft.copy.pendingChangeCount() + (dryRun ? changes.size() : 0), "stateHash", candidate.contentHash(),
        "changes", changes.stream().map(c -> c.getClass().getSimpleName()).toList()));
    Map<String, Object> territories = territoryPreview(before.countries(), candidate.countries(), changes);
    if (!territories.isEmpty()) result.put("territory", territories);
    if (!Objects.equals(before.simulationTime(), candidate.simulationTime())
        || !before.formations().marches().equals(candidate.formations().marches())) {
      putClock(result, committedClock(candidate.simulationTime()));
      result.put("marches", WorldQueries.marches(candidate, draft.copy.baseNode().ref()));
    }
    var battles = changes.stream().filter(WorldChange.ResolveBattle.class::isInstance)
        .map(change -> ((WorldChange.ResolveBattle) change).battle()).toList();
    if (!battles.isEmpty()) result.put("battles", battles);
    result = Map.copyOf(result);
    draft.requests.put(requestId, new Applied(requestHash, result));
    return result;
  }

  private static Map<String, Object> territoryPreview(CountryState before, CountryState after, List<WorldChange> changes) {
    var provinces = new java.util.TreeSet<String>();
    var checked = new HashSet<List<String>>();
    var conflicts = new ArrayList<Map<String, String>>();
    int count = 0;
    for (WorldChange change : changes) {
      if (!(change instanceof WorldChange.SetTerritory op)) continue;
      for (String id : op.provinceIds()) {
        provinces.add(id);
        if (!checked.add(List.of(op.field(), id))) continue;
        String old = countryAt(before, id, op.field());
        String next = countryAt(after, id, op.field());
        if (old == null || old.equals(next)) continue;
        count++;
        if (conflicts.size() < 100) {
          var conflict = new LinkedHashMap<>(Map.of("provinceId", id, "field", op.field(), "from", old));
          if (next != null) conflict.put("to", next);
          conflicts.add(Map.copyOf(conflict));
        }
      }
    }
    if (provinces.isEmpty()) return Map.of();
    return Map.of("provinceIds", List.copyOf(provinces), "conflictCount", count, "conflicts", List.copyOf(conflicts));
  }

  private static String countryAt(CountryState state, String provinceId, String field) {
    var territory = state.territories().get(provinceId);
    if (territory == null) return null;
    var assignment = field.equals("owner") ? territory.owner() : territory.controller();
    return assignment == null ? null : assignment.countryId();
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
      var result = nodeSummary(requireNode(existing.get().node()));
      result.put("replayedReceipt", true);
      return result;
    }
    Draft draft = draft(actor, draftId);
    requireVersion(draft, expectedVersion);
    NodeRef ref = new NodeRef(draft.copy.targetBranch(), tick);
    CommitReceipt receipt = new CommitReceipt(actor, requestId, requestHash, ref);
    TimelineNode saved = timeline.save(draft.copy, tick, actor, baseHash, receipt);
    StaticWorldState state = draft.copy.currentState();
    if (state.simulationTime() != null) clocks.put(saved.ref(), committedClock(state.simulationTime()));
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

  private StaticWorldState load(NodeRef ref) { return timeline.loadCached(ref); }
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
  private Map<String, Object> nodeSummary(TimelineNode node) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("node", node.ref().label()); value.put("parent", node.parent() == null ? null : node.parent().label());
    value.put("stateHash", node.stateHash()); value.put("savedBy", node.savedBy()); value.put("savedAt", node.savedAtEpochMilli());
    putClock(value, clock(node.ref()));
    return value;
  }

  private static void putClock(Map<String, Object> result, Clock clock) {
    if (clock != null) result.put("clock", clock);
  }

  private static Clock committedClock(String time) {
    return time == null ? null : new Clock(time, "second", null);
  }

  private Clock clock(NodeRef ref) {
    var pending = new ArrayDeque<TimelineNode>();
    NodeRef cursor = ref;
    while (cursor != null && !clocks.containsKey(cursor)) {
      TimelineNode node = requireNode(cursor);
      pending.push(node);
      cursor = node.parent();
    }
    Clock current = clocks.get(cursor);
    while (!pending.isEmpty()) {
      TimelineNode node = pending.pop();
      String time = node.parent() == null ? load(node.ref()).simulationTime() : explicitTime(node);
      if (time != null) current = committedClock(time);
      else if (current == null || current.precision().equals("date")) {
        Clock reference = calendar().get(node.ref());
        if (reference != null) current = reference;
      }
      clocks.put(node.ref(), current);
    }
    return current;
  }

  private static String explicitTime(TimelineNode node) {
    var changes = WorldChangeSet.fromJson(node.changeSetJson()).changes();
    for (var change : changes.reversed()) {
      if (change instanceof WorldChange.SetSimulationTime clock) return clock.time();
      if (change instanceof WorldChange.StartMarch march) return march.march().startedAt();
      if (change instanceof WorldChange.ResolveBattle battle) return battle.battle().endedAt();
    }
    return null;
  }

  private Map<NodeRef, Clock> calendar() {
    if (calendar != null) return calendar;
    var path = store.world().resolve("scenario-calendar.json");
    if (Files.isRegularFile(path)) {
      try {
        var document = JsonCodec.fromJson(Files.readString(path), ScenarioCalendar.class);
        if (document.formatVersion() != 1) throw new IllegalArgumentException("未知剧本日期参考版本");
        var entries = new HashMap<NodeRef, Clock>();
        for (var anchor : document.anchors()) {
          NodeRef ref = node(anchor.node());
          if (!requireNode(ref).stateHash().equals(anchor.stateHash())) {
            throw new IllegalArgumentException("日期参考与节点 hash 不一致: " + ref.label());
          }
          String time = SimulationTime.normalize(LocalDate.parse(anchor.date()) + "T00:00:00");
          var value = new Clock(time, "date", requireText(anchor.source(), "source"));
          if (entries.putIfAbsent(ref, value) != null) throw new IllegalArgumentException("日期参考节点重复: " + ref.label());
        }
        calendar = Map.copyOf(entries);
        return calendar;
      } catch (IOException | RuntimeException e) {
        System.err.println("无法读取剧本日期参考: " + e.getMessage());
      }
    }
    calendar = Map.of();
    return calendar;
  }
}
