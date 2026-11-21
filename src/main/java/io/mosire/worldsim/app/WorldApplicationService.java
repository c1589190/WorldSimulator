package io.mosire.worldsim.app;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.country.CountryState;
import io.mosire.worldsim.info.TurnState;
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
  private record EventPreview(long version, String commandHash, WorldEventTimeline.Plan plan) {}
  private record GapScope(NodeRef node, boolean referenceOnly) {}
  private record TerritoryGap(String provinceId, String stableId, String address, String name, String stateId,
                              String stateName, String expectedCountryId, String actualCountryId, String issue) {}
  private static final class Draft {
    final String actor;
    final TimelineWorkingCopy copy;
    final boolean eventTimeline;
    final Map<String, Applied> requests = new HashMap<>();
    long version;
    Preview preview;
    WorldEventTimeline.Plan events;
    EventPreview eventPreview;
    Draft(String actor, TimelineWorkingCopy copy, boolean eventTimeline) {
      this.actor = actor;
      this.copy = copy;
      this.eventTimeline = eventTimeline;
    }
  }

  private final FileTimelineStore store;
  private final TimelineService timeline;
  private final Map<NodeRef, Clock> clocks = new HashMap<>();
  private final Map<NodeRef, TurnPosition> turnPositions = new HashMap<>();
  private record TurnPosition(String id, NodeRef opening, NodeRef resolution) {}
  private io.mosire.worldsim.country.CivicState censusState;
  private Map<String, List<io.mosire.worldsim.country.CivicState.Census>> censusByProvince = Map.of();
  private Map<NodeRef, Clock> calendar;
  private WorldGeography geography;
  private WorldMap linksMap;
  private LandLinks links;
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

  public synchronized Object population(String label, String date, String basis, String scope) {
    var address = scope == null ? null : NodeAddress.parse(scope);
    var saved = savedMap(label == null && address != null ? address.node().label() : label);
    return WorldCivic.population(saved, date, basis, address);
  }

  public synchronized Object info(String label) {
    var saved = savedMap(label);
    return Map.of("node", saved.node().label(), "stateHash", saved.stateHash(),
        "entries", WorldTurn.info(saved.state(), saved.node()));
  }

  public synchronized Object turn(String text) {
    var address = NodeAddress.parse(text);
    var state = load(address.node());
    var turn = state.turns().turns().get(WorldTurn.turnId(address));
    if (turn == null) throw new TimelineException("ENTITY_NOT_FOUND", "回合不存在");
    var result = new LinkedHashMap<String, Object>(Map.of("address", address.canonical(), "turn", turn));
    if (turn.resolution() == null && isTurnOpening(address)) result.put("received", submissions(address));
    return result;
  }

  public synchronized Object decisionPanel(SavedMap saved, NodeAddress subject) {
    if (!subject.node().equals(saved.node())) throw new IllegalArgumentException("对象地址须与当前节点一致");
    var path = subject.requireEntityPath().path();
    var kind = String.join("/", path.subList(0, path.size() - 1));
    var target = new TurnState.Subject(kind, path.getLast());
    var position = panelTurnPosition(saved);
    var turn = position == null ? null : saved.state().turns().turns().get(position.id());
    if (turn == null) {
      return new WorldPanels.DecisionPanel(saved.node().label(), saved.stateHash(), subject.canonical(),
          null, null, "no-turn", List.of(), null, subjectName(saved.state(), target));
    }
    var opening = new NodeAddress(position.opening(), List.of("turn", position.id()));
    boolean live = turn.resolution() == null && opening.node().equals(saved.node())
        && store.head(saved.node().branch()).filter(saved.node()::equals).isPresent();
    boolean settled = turn.resolution() != null;
    boolean inboxView = !settled && !turn.proposals().isEmpty();
    var brief = turn.brief();
    var decisions = new ArrayList<WorldPanels.Decision>();
    for (String id : new java.util.TreeSet<>(brief.ids())) {
      var participant = brief.participant(id);
      if (!participant.subject().equals(target)) continue;
      var receipt = live || settled || inboxView && turn.proposals().containsKey(id) ? receipt(opening, brief, id) : null;
      var proposal = settled || inboxView ? turn.proposals().get(id) : receipt == null ? null : receivedProposal(receipt, brief, id);
      var targetAddresses = proposal == null ? List.<String>of() :
          proposal.targets().stream().map(p -> opening.node().label() + "/" + p).toList();
      var rulings = turn.rulings() == null ? List.<TurnState.Ruling>of() :
          turn.rulings().stream().filter(item -> id.equals(item.decisionMakerId())).toList();
      decisions.add(new WorldPanels.Decision(id, participantName(opening.node(), brief, id),
          settled ? "resolved" : proposal == null ? "pending" : "received",
          proposal, targetAddresses, receipt == null ? null : receipt.ref().label(),
          receipt == null ? null : receipt.savedAtEpochMilli(), rulings));
    }
    String mode = settled ? "settled" : inboxView ? "receipt" : live ? "receiving" : "historical";
    String resolved = position.resolution() == null ? null : WorldQueries.address(position.resolution(), "turn", position.id());
    return new WorldPanels.DecisionPanel(saved.node().label(), saved.stateHash(), subject.canonical(),
        opening.canonical(), resolved, mode, List.copyOf(decisions), decisions.isEmpty() ? null : turn.resolution(),
        subjectName(decisions.isEmpty() ? saved.state() : load(opening.node()), target));
  }

  public synchronized Object roundRulings(SavedMap saved) {
    var position = panelTurnPosition(saved);
    if (position == null) return new WorldPanels.RoundRulings(saved.node().label(), saved.stateHash(), null, null, null, List.of());
    var turn = saved.state().turns().turns().get(position.id());
    String opening = WorldQueries.address(position.opening(), "turn", position.id());
    String resolved = position.resolution() == null ? null : WorldQueries.address(position.resolution(), "turn", position.id());
    var rulings = turn.rulings() == null ? List.<WorldPanels.RoundRuling>of() : turn.rulings().stream().map(ruling -> {
      var id = ruling.decisionMakerId();
      if (id == null) return new WorldPanels.RoundRuling(ruling, null, null);
      var participant = turn.brief().participant(id);
      return new WorldPanels.RoundRuling(ruling, saved.node().label() + "/" + participant.subject().path(),
          participantName(position.opening(), turn.brief(), id));
    }).toList();
    return new WorldPanels.RoundRulings(saved.node().label(), saved.stateHash(), opening, resolved, turn.resolution(), rulings);
  }

  private TurnPosition panelTurnPosition(SavedMap saved) {
    return saved.state().turns().turns().isEmpty() ? null : turnPosition(saved.node());
  }

  private String participantName(NodeRef opening, TurnState.Brief brief, String id) {
    var participant = brief.participant(id);
    return brief.legacy() ? subjectName(load(opening), participant.subject()) : participant.displayName();
  }

  private static String subjectName(StaticWorldState state, TurnState.Subject subject) {
    if (subject.kind().equals("country")) {
      var country = state.countries().countries().get(subject.id());
      return country == null ? subject.id() : country.name();
    }
    if (subject.kind().equals("orbat/command")) {
      var command = state.formations().commands().get(subject.id());
      return command == null ? subject.id() : command.name();
    }
    var unit = state.formations().units().get(subject.id());
    return unit == null ? subject.id() : unit.name();
  }

  private TurnPosition turnPosition(NodeRef ref) {
    var pending = new ArrayDeque<TimelineNode>();
    NodeRef cursor = ref;
    while (cursor != null && !turnPositions.containsKey(cursor)) {
      var node = requireNode(cursor);
      pending.push(node);
      cursor = node.parent();
    }
    TurnPosition position = turnPositions.get(cursor);
    while (!pending.isEmpty()) {
      var node = pending.pop();
      for (var change : WorldChangeSet.fromJson(node.changeSetJson()).changes()) {
        if (change instanceof WorldChange.OpenTurn opened) {
          position = new TurnPosition(opened.brief().id(), node.ref(), null);
        } else if (change instanceof WorldChange.ResolveTurn resolved && position != null && position.id().equals(resolved.turnId())) {
          position = new TurnPosition(position.id(), position.opening(), node.ref());
        }
      }
      turnPositions.put(node.ref(), position);
    }
    return position;
  }

  public synchronized Object provincePopulation(SavedMap saved, NodeAddress address) {
    var province = panelProvince(saved, address);
    var civic = saved.state().civics();
    if (censusState != civic) {
      censusByProvince = civic.census().values().stream().collect(Collectors.groupingBy(
          io.mosire.worldsim.country.CivicState.Census::provinceId));
      censusState = civic;
    }
    var stamp = clock(saved.node());
    String date = stamp == null ? null : stamp.time().substring(0, 10);
    var records = censusByProvince.getOrDefault(province.id(), List.of()).stream()
        .filter(record -> date == null || record.date().compareTo(date) <= 0)
        .sorted(Comparator.comparing(io.mosire.worldsim.country.CivicState.Census::date).reversed()
            .thenComparing(io.mosire.worldsim.country.CivicState.Census::basis)).toList();
    return new WorldPanels.Population(saved.node().label(), saved.stateHash(), address.canonical(), records);
  }

  public synchronized Object provinceLogistics(SavedMap saved, NodeAddress address) {
    var province = panelProvince(saved, address);
    var tracks = saved.map().railways().values().stream().filter(railway -> railway.provincePath().contains(province.id()))
        .map(railway -> {
          var operation = railway.operation();
          return new WorldPanels.Track(WorldQueries.address(saved.node(), "map", "railway", railway.id()), railway.id(),
              railway.level(), operation == null ? "unknown" : operation.status(),
              operation == null ? null : operation.tonnesPerDay(), operation == null ? null : operation.evidence());
        }).toList();
    var hubs = saved.map().supplyHubs().values().stream().filter(hub -> hub.provinceId().equals(province.id()))
        .map(hub -> new WorldPanels.Hub(WorldQueries.address(saved.node(), "map", "supplyHub", hub.id()), hub)).toList();
    var borders = new ArrayList<WorldPanels.Border>();
    if (province.kind().equals("land")) {
      var network = landLinks(saved);
      for (String id : network.neighbors(province.id())) {
        var other = saved.map().provinces().get(id);
        if (other == null || !other.kind().equals("land")) continue;
        var edge = network.edge(province, other);
        borders.add(new WorldPanels.Border(WorldQueries.address(saved.node(), "map", "province", id), other.name(), edge.river()));
      }
    }
    return new WorldPanels.Logistics(saved.node().label(), saved.stateHash(), address.canonical(), tracks, hubs, List.copyOf(borders));
  }

  private static Province panelProvince(SavedMap saved, NodeAddress address) {
    var path = address.path();
    if (!address.node().equals(saved.node()) || path.size() != 3 || !path.subList(0, 2).equals(List.of("map", "province"))) {
      throw new IllegalArgumentException("请输入当前节点的省份地址");
    }
    var province = saved.map().provinces().get(path.getLast());
    if (province == null) throw new TimelineException("ENTITY_NOT_FOUND", "省份不存在");
    return province;
  }

  private boolean isTurnOpening(NodeAddress address) {
    return WorldChangeSet.fromJson(requireNode(address.node()).changeSetJson()).changes().stream()
        .anyMatch(change -> change instanceof WorldChange.OpenTurn opened && opened.brief().id().equals(WorldTurn.turnId(address)));
  }

  private Map<String, TurnState.Proposal> submissions(NodeAddress address) {
    var turn = load(address.node()).turns().requireOpen(WorldTurn.turnId(address));
    if (!isTurnOpening(address)) throw new TimelineException("TURN_BASE_REQUIRED", "请选择公布开放回合的节点");
    var proposals = new TreeMap<String, TurnState.Proposal>();
    for (String id : turn.brief().ids()) {
      var receipt = receipt(address, turn.brief(), id);
      if (receipt != null) proposals.put(id, receivedProposal(receipt, turn.brief(), id));
    }
    return Map.copyOf(proposals);
  }

  private TimelineNode receipt(NodeAddress opening, TurnState.Brief brief, String id) {
    return store.head(WorldTurn.inbox(opening, id, brief.legacy())).map(ref -> {
      var node = requireNode(ref);
      if (!opening.node().equals(node.parent())) throw new TimelineException("TURN_INBOX_INVALID", "收件与开放回合不一致");
      return node;
    }).orElse(null);
  }

  private TurnState.Proposal receivedProposal(TimelineNode receipt, TurnState.Brief brief, String id) {
    var inbox = load(receipt.ref()).turns().requireOpen(brief.id());
    var proposal = inbox.proposals().get(id);
    if (!inbox.brief().equals(brief) || proposal == null) throw new TimelineException("TURN_INBOX_INVALID", "收件与开放回合不一致");
    return proposal;
  }

  synchronized WorldTurn.DecisionSession decisionSession(String turnText, String id, boolean legacy) {
    var address = NodeAddress.parse(turnText);
    var turn = load(address.node()).turns().requireOpen(WorldTurn.turnId(address));
    if (!isTurnOpening(address) || turn.brief().legacy() != legacy || turn.brief().participant(id) == null) {
      throw new TimelineException("DECISION_ACCESS_DENIED", "决策会话需要绑定开放节点及参与身份");
    }
    return new WorldTurn.DecisionSession(address, id, legacy, Boolean.TRUE.equals(turn.brief().scoped()));
  }

  synchronized Object decisionBrief(WorldTurn.DecisionSession session) {
    var saved = savedMap(session.turn().node().label());
    var brief = saved.state().turns().requireOpen(WorldTurn.turnId(session.turn())).brief();
    var participant = brief.participant(session.participantId());
    var result = new LinkedHashMap<String, Object>(Map.of("turnAddress", session.turn().canonical(),
        "baseHash", saved.stateHash(),
        "addresses", participant.readablePaths().stream().map(path -> saved.node().label() + "/" + path).toList(),
        "entries", WorldTurn.decisionInfo(saved.state(), saved.node(), session)));
    if (session.legacy()) result.put("countryId", session.participantId());
    else {
      result.put("decisionMakerId", session.participantId());
      result.put("displayName", participant.displayName());
      result.put("subjectAddress", saved.node().label() + "/" + participant.subject().path());
      result.put("proposalTargets", participant.proposalTargets().stream().map(path -> saved.node().label() + "/" + path).toList());
    }
    if (Boolean.TRUE.equals(brief.scoped())) {
      var view = new WorldPerspective(saved, participant, session.participantId());
      result.put("documents", view.documents());
      result.put("entries", WorldTurn.decisionInfo(saved.state(), saved.node(), session).stream().map(entry ->
          new WorldTurn.DecisionInfo(entry.address(), entry.subjectAddresses().stream().filter(text -> view.canRead(NodeAddress.parse(text))).toList(),
              entry.title(), entry.text(), entry.type(), entry.payload(), entry.evidence())).toList());
    }
    putClock(result, clock(saved.node()));
    return result;
  }

  synchronized Object decisionLookup(WorldTurn.DecisionSession session, String entity) {
    var saved = savedMap(session.turn().node().label());
    var brief = saved.state().turns().requireOpen(WorldTurn.turnId(session.turn())).brief();
    var stamp = clock(saved.node());
    if (Boolean.TRUE.equals(brief.scoped())) {
      return new WorldPerspective(saved, brief.participant(session.participantId()), session.participantId())
          .lookup(NodeAddress.parse(entity), stamp == null ? null : stamp.time());
    }
    var address = WorldTurn.readable(NodeAddress.parse(entity), session, brief);
    return WorldTurn.entity(saved.state(), address, saved.stateHash(), stamp == null ? null : stamp.time());
  }

  synchronized List<WorldPerspective.Marker> decisionMarkers(WorldTurn.DecisionSession session) {
    var saved = savedMap(session.turn().node().label());
    var brief = saved.state().turns().requireOpen(WorldTurn.turnId(session.turn())).brief();
    if (!Boolean.TRUE.equals(brief.scoped())) throw new TimelineException("DECISION_ACCESS_DENIED", "此回合未启用可见面");
    return new WorldPerspective(saved, brief.participant(session.participantId()), session.participantId()).markers();
  }

  synchronized Map<String, Object> decisionSearch(WorldTurn.DecisionSession session, String query, int limit) {
    requirePage(0, limit);
    var saved = savedMap(session.turn().node().label());
    var brief = saved.state().turns().requireOpen(WorldTurn.turnId(session.turn())).brief();
    var view = new WorldPerspective(saved, brief.participant(session.participantId()), session.participantId());
    return search.computeIfAbsent(saved.node(), ignored -> new WorldSearchIndex(saved.state())).search(
        saved.node(), saved.stateHash(), query, null, 0, limit, text -> view.canRead(NodeAddress.parse(text)));
  }

  public synchronized List<?> mapFeatures(SavedMap saved) {
    return WorldPerspective.markersForGM(saved);
  }

  public synchronized List<WorldPerspective.UnitSight> unitPerspective(SavedMap saved, NodeAddress address) {
    var path = address.path();
    if (!saved.node().equals(address.node()) || path.size() != 3
        || !path.subList(0, 2).equals(List.of("orbat", "unit"))) {
      throw new IllegalArgumentException("请选择当前节点的部署单位地址");
    }
    var unitId = path.getLast();
    if (!saved.state().formations().units().containsKey(unitId)) {
      throw new TimelineException("ENTITY_NOT_FOUND", "单位不存在：" + unitId);
    }
    var position = panelTurnPosition(saved);
    if (position == null) return List.of();
    var brief = saved.state().turns().turns().get(position.id()).brief();
    if (!Boolean.TRUE.equals(brief.scoped())) return List.of();
    var subject = new TurnState.Subject("orbat/unit", unitId);
    return brief.ids().stream().sorted().filter(id -> brief.participant(id).subject().equals(subject))
        .map(id -> new WorldPerspective(saved, brief.participant(id), id).unitSight()).toList();
  }

  synchronized Object submitDecision(WorldTurn.DecisionSession session, String text, String rationale, List<String> targets,
                                     List<TurnState.Movement> movements, List<WorldTurn.ContactDraft> contacts) {
    var opening = session.turn();
    var base = requireNode(opening.node());
    var state = load(opening.node());
    String id = WorldTurn.turnId(opening);
    var brief = state.turns().requireOpen(id).brief();
    var current = savedMap(opening.node().label());
    var proposal = WorldTurn.proposal(session, brief, current, text, rationale, targets, movements, contacts);
    String participant = session.participantId();
    var inbox = WorldTurn.inbox(opening, participant, session.legacy());
    var existing = receipt(opening, brief, participant);
    if (existing != null) {
      if (!proposal.equals(receivedProposal(existing, brief, participant))) {
        throw new TimelineException("DECISION_ALREADY_SUBMITTED", "已收件，原决策须保持一致");
      }
      return Map.of("turnAddress", opening.canonical(), "receipt", existing.ref().label(), "replayedReceipt", true);
    }
    if (store.head(opening.node().branch()).filter(opening.node()::equals).isEmpty()) {
      throw new TimelineException("TURN_CLOSED", "正式分支已推进，当前回合停止收件");
    }
    var copy = timeline.openFork(opening.node(), inbox, base.stateHash(), state);
    var change = session.legacy() ? new WorldChange.SubmitDecision(id, participant, proposal)
        : new WorldChange.SubmitDecision(id, null, proposal, participant);
    String actor = "dm:" + participant;
    NodeRef receipt;
    if (base.eventTime() == null) {
      timeline.applyAll(copy, List.of(change));
      receipt = timeline.save(copy, Math.incrementExact(opening.node().tick()), actor, base.stateHash()).ref();
    } else {
      String time = SimulationTime.format(java.time.LocalDateTime.parse(base.eventTime()).plusSeconds(1));
      var plan = WorldEventTimeline.plan(copy, commandContext(base),
          List.of(new WorldCommand.AdvanceTime(0, time), new WorldCommand.PersistedChange(change)));
      String hash = JsonCodec.sha256(JsonCodec.canonicalJson(proposal));
      receipt = timeline.saveEvents(copy, plan.steps(), actor, base.stateHash(), inbox.value(), hash).nodes().getLast().ref();
    }
    return Map.of("turnAddress", opening.canonical(), "receipt", receipt.label(), "replayedReceipt", false);
  }

  public synchronized Map<String, Object> lookup(String text, boolean includeGeometry) {
    NodeAddress address = NodeAddress.parse(text);
    StaticWorldState state = load(address.node());
    String hash = requireNode(address.node()).stateHash();
    var path = address.requireEntityPath().path();
    if (path.size() == 3 && (path.get(0).equals("info") && Set.of("document", "contact").contains(path.get(1))
        || path.get(0).equals("orbat") && path.get(1).equals("command"))) {
      Object item = switch (path.get(1)) {
        case "document" -> state.intelligence().documents().get(path.getLast());
        case "contact" -> state.intelligence().contacts().get(path.getLast());
        default -> state.formations().commands().get(path.getLast());
      };
      if (item == null) throw new TimelineException("ENTITY_NOT_FOUND", "实体不存在: " + address.canonical());
      String title = switch (item) {
        case io.mosire.worldsim.info.InfoState.Contact contact -> contact.title();
        case io.mosire.worldsim.info.InfoState.Document document -> document.title();
        case io.mosire.worldsim.orbat.Headquarters hq -> hq.name();
        default -> throw new IllegalStateException();
      };
      return Map.of("address", address.canonical(), "nodeHash", hash, "kind", path.get(1), "displayName", title, "entity", item);
    }
    if (path.size() == 2 && path.getFirst().equals("country")) {
      var stamp = clock(address.node());
      var saved = new SavedMap(address.node(), hash, state);
      var result = WorldCivic.profile(saved, address, stamp == null ? null : stamp.time());
      result.put("intelligenceDocuments", state.intelligence().documents().values().stream()
          .filter(document -> document.countryId().equals(path.getLast()))
          .sorted(Comparator.comparing(io.mosire.worldsim.info.InfoState.Document::id))
          .map(document -> Map.of("title", document.title(), "address",
              WorldQueries.address(address.node(), "info", "document", document.id())))
          .toList());
      if (state.countries().railEconomy() != null) result.put("railCapacity", RailAllocation.capacity(saved, path.getLast(), null, null));
      return result;
    }
    if (path.size() == 3 && path.getFirst().equals("info")) {
      var entry = state.intelligence().entries().get(path.get(1) + "/" + path.get(2));
      if (entry == null) throw new TimelineException("ENTITY_NOT_FOUND", "INFO 条目不存在");
      return Map.of("nodeHash", hash, "info", WorldTurn.infoView(address.node(), entry));
    }
    if (path.size() == 3 && path.subList(0, 2).equals(List.of("diplomacy", "treaty"))) {
      var treaty = state.civics().treaties().get(path.getLast());
      if (treaty == null) throw new TimelineException("ENTITY_NOT_FOUND", "条约不存在");
      var stamp = clock(address.node());
      return Map.of("address", address.canonical(), "nodeHash", hash,
          "treaty", treaty, "status", treaty.statusAt(stamp == null ? null : stamp.time()));
    }
    if (!path.getFirst().equals("map")) {
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
    if (resolved.value() instanceof io.mosire.worldsim.map.MapFeature feature) {
      result.put("point", state.map().provinces().get(feature.provinceId()).displayPoint());
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
    return territoryGaps(nodeLabel, scope, countryId, stateId, offset, limit, null);
  }

  public synchronized Map<String, Object> territoryGaps(String nodeLabel, String scope, String countryId,
                                                         String stateId, int offset, int limit, List<String> expectedProvinceIds) {
    requirePage(offset, limit);
    if (scope != null && !Set.of("reference", "all").contains(scope)) {
      throw new IllegalArgumentException("scope 须为 reference 或 all");
    }
    SavedMap saved = savedMap(nodeLabel);
    var reference = territoryReference(saved);
    boolean provided = expectedProvinceIds != null;
    boolean referenceOnly = "reference".equals(scope) || scope == null && reference != null;
    Map<String, String> expected = provided ? territoryExpectation(saved, countryId, expectedProvinceIds)
        : reference == null ? Map.of() : reference.owners();
    var ids = provided || referenceOnly || countryId != null ? expected.keySet() : saved.map().provinces().keySet();
    int checked = 0;
    int unassigned = 0;
    int matches = 0;
    var page = new ArrayList<TerritoryGap>(limit);
    for (String id : ids.stream().sorted().toList()) {
      var province = saved.map().provinces().get(id);
      if (province == null || !province.kind().equals("land")
          || countryId != null && !countryId.equals(expected.get(id))
          || stateId != null && !stateId.equals(province.stateId())) continue;
      checked++;
      var territory = saved.state().countries().territories().get(id);
      String owner = territory == null || territory.owner() == null ? null : territory.owner().countryId();
      String wanted = expected.get(id);
      if (owner == null || wanted != null && !wanted.equals(owner)) {
        if (owner == null) unassigned++;
        if (matches >= offset && page.size() < limit) {
          var state = province.stateId() == null ? null : saved.map().states().get(province.stateId());
          page.add(new TerritoryGap(id, "province:" + id, WorldQueries.address(saved.node(), "map", "province", id),
              province.name(), province.stateId(), state == null ? null : state.name(), wanted, owner,
              owner == null ? "unassigned" : "owner-mismatch"));
        }
        matches++;
      }
    }
    String coverage = checked == 0 ? "none" : provided ? "provided" : referenceOnly || countryId != null ? "reference" : "all";
    var result = new LinkedHashMap<String, Object>(Map.of(
        "node", saved.node().label(), "stateHash", saved.stateHash(), "scope", provided ? "provided" : referenceOnly ? "reference" : "all",
        "coverage", coverage, "checkedProvinces", checked, "unassigned", unassigned, "ownershipMismatches", matches - unassigned,
        "offset", Math.min(offset, matches), "total", matches, "gaps", List.copyOf(page)));
    if (reference != null && !provided) {
      result.put("referenceDate", reference.date());
      result.put("source", reference.source());
    }
    return result;
  }

  private static Map<String, String> territoryExpectation(SavedMap saved, String countryId, List<String> ids) {
    NodeAddress.requireSegment(countryId);
    var expected = new HashMap<String, String>();
    for (String id : ids) {
      var province = saved.map().provinces().get(id);
      if (province == null || !province.kind().equals("land")) {
        throw new IllegalArgumentException("待查省份须是当前地图的陆省: " + id);
      }
      expected.put(id, countryId);
    }
    return expected;
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

  synchronized WorldMovement.Plan landRoute(String unit, String destination, List<String> via) {
    var address = NodeAddress.parse(unit);
    var saved = savedMap(address.node().label());
    return WorldMovement.plan(saved, address, destination, via, geography(saved), landLinks(saved));
  }

  synchronized WorldMovement.Estimate movementEstimate(String text, String role, List<String> route) {
    var address = NodeAddress.parse(text);
    var saved = savedMap(address.node().label());
    return WorldMovement.estimate(saved, address, role == null ? "actual" : role, route,
        route == null ? null : geography(saved), route == null ? null : landLinks(saved));
  }

  synchronized WorldBattle.Context battleContext(String provinceAddress, List<String> attackers, List<String> defenders) {
    var saved = savedMap(NodeAddress.parse(provinceAddress).node().label());
    return WorldBattle.context(saved, commandContext(requireNode(saved.node())), provinceAddress, attackers, defenders);
  }

  private LandLinks landLinks(SavedMap saved) {
    if (linksMap != saved.map()) {
      links = new LandLinks(saved.map(), riverReference(saved));
      linksMap = saved.map();
    }
    return links;
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

  synchronized Map<String, Object> inventory(String text, boolean details) {
    var address = NodeAddress.parse(text);
    return WorldInventory.read(savedMap(address.node().label()), address, details);
  }

  synchronized Map<String, Object> railCapacity(String text, List<String> route, String itemId) {
    var address = NodeAddress.parse(text);
    var saved = savedMap(address.node().label());
    var country = WorldQueries.country(saved.state(), address);
    var capacity = RailAllocation.capacity(saved, country.id(), route, route == null ? null : landLinks(saved));
    var result = new LinkedHashMap<String, Object>(Map.of("stateHash", saved.stateHash(), "capacity", capacity));
    if (itemId != null) {
      NodeAddress.requireSegment(itemId);
      var rail = saved.state().countries().railEconomy();
      var weight = rail == null ? null : rail.rules().itemWeights().get(itemId);
      result.put("itemId", itemId);
      result.put("itemWeightStatus", weight == null ? "unknown" : "configured");
      if (weight != null) {
        result.put("itemWeight", weight);
        if (capacity.remainingKg() != null) result.put("piecesByCapacity", capacity.remainingKg() / weight.kilograms());
      }
    }
    return Map.copyOf(result);
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
    int total = path.size() - at - 1;
    int start = Math.min(offset, total);
    int end = start + Math.min(limit, total - start);
    List<Map<String, Object>> changes = new ArrayList<>();
    for (int i = start; i < end; i++) {
      var n = path.get(at + 1 + i);
      var operations = WorldChangeSet.fromJson(n.changeSetJson()).changes();
      Map<String, Integer> kinds = new TreeMap<>();
      operations.forEach(op -> kinds.merge(op.getClass().getSimpleName(), 1, Integer::sum));
      var entry = new LinkedHashMap<String, Object>(Map.of("node", n.ref().label(), "changeCount", operations.size(), "kinds", kinds));
      var economy = economyChanges(operations);
      if (!economy.isEmpty()) entry.put("militaryEconomy", economy);
      changes.add(entry);
    }
    return Map.of("from", from.label(), "to", to.label(), "total", total, "offset", start, "nodes", changes);
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
    return open(actor, base, expectedHash, forkBranch, false);
  }

  public synchronized Map<String, Object> open(String actor, String base, String expectedHash, String forkBranch,
      boolean eventTimeline) {
    requireText(actor, "actor"); requireText(expectedHash, "expectedHash");
    NodeRef ref = node(base);
    StaticWorldState prepared = load(ref);
    TimelineWorkingCopy copy = forkBranch == null
        ? timeline.openNext(ref, expectedHash, prepared)
        : timeline.openFork(ref, new BranchId(requireText(forkBranch, "forkBranch")), expectedHash, prepared);
    String id = UUID.randomUUID().toString();
    boolean events = eventTimeline || copy.baseNode().eventTime() != null;
    drafts.put(id, new Draft(actor, copy, events));
    var result = new LinkedHashMap<String, Object>(Map.of("draftId", id, "draftVersion", 0, "base", ref.label(),
        "baseHash", copy.baseNode().stateHash(), "targetBranch", copy.targetBranch().value(),
        "mode", copy.kind().name().toLowerCase(), "eventTimeline", events));
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
    if (draft.eventTimeline) {
      var result = applyEvents(draft, draftId, commandHash, commands, dryRun);
      draft.requests.put(requestId, new Applied(requestHash, result));
      return result;
    }
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
      var context = commandContext(draft.copy.baseNode());
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
    var economy = economyChanges(changes);
    if (!economy.isEmpty()) result.put("militaryEconomy", economy);
    result = Map.copyOf(result);
    draft.requests.put(requestId, new Applied(requestHash, result));
    return result;
  }

  private WorldCommand.Context commandContext(TimelineNode base) {
    return new WorldCommand.Context(base.ref(), base.stateHash(), () -> {
      Clock stamp = clock(base.ref());
      return stamp == null ? null : stamp.time();
    }, this::geography, this::landLinks, this::submissions);
  }

  private Map<String, Object> applyEvents(Draft draft, String draftId, String commandHash,
      List<Map<String, Object>> commands, boolean dryRun) {
    var preview = draft.eventPreview;
    WorldEventTimeline.Plan plan;
    if (preview != null && preview.version() == draft.version && preview.commandHash().equals(commandHash)) {
      plan = preview.plan();
    } else {
      var combined = new ArrayList<WorldCommand>();
      if (draft.events != null) combined.addAll(draft.events.commands());
      commands.stream().map(WorldCommand::parse).forEach(combined::add);
      plan = WorldEventTimeline.plan(draft.copy, commandContext(draft.copy.baseNode()), combined);
    }
    var state = plan.state();
    long version = draft.version + (dryRun ? 0 : 1);
    var result = Map.<String, Object>of(
        "draftId", draftId, "draftVersion", version, "dryRun", dryRun,
        "appliedCount", commands.size(), "pendingCount", plan.commands().size(),
        "stateHash", state.contentHash(), "clock", committedClock(state.simulationTime()),
        "marches", WorldQueries.marches(state, draft.copy.baseNode().ref()),
        "steps", plan.steps().stream().map(step -> Map.of("time", step.event().time(), "events", eventSummary(step, true))).toList());
    if (dryRun) {
      draft.eventPreview = new EventPreview(version, commandHash, plan);
    } else {
      draft.events = plan;
      draft.eventPreview = null;
      draft.version = version;
    }
    return result;
  }

  private static List<Map<String, Object>> eventSummary(WorldChangeSet step, boolean includeEconomyDetails) {
    var events = new ArrayList<Map<String, Object>>();
    for (WorldChange change : step.changes()) {
      switch (change) {
        case WorldChange.AdvanceEvent event -> {
          for (var boundary : event.treatyBoundaries()) {
            events.add(Map.of("type", "treaty-" + boundary.status(), "id", boundary.treatyId()));
          }
          if (event.railPeriod() != null) events.add(Map.of("type", "rail-period-opened", "periodIndex", event.railPeriod()));
          for (var arrival : event.arrivals()) {
            boolean blocked = arrival.passage() != null && arrival.passage().blocked();
            events.add(Map.of("type", blocked ? "march-blocked" : "march-arrived",
                "unitId", arrival.unitId(), "legIndex", arrival.legIndex()));
          }
        }
        case WorldChange.OpenTurn opened -> events.add(Map.of("type", "turn-opened", "id", opened.brief().id()));
        case WorldChange.ResolveTurn resolved -> events.add(Map.of("type", "turn-resolved", "id", resolved.turnId()));
        case WorldChange.ResolveBattle resolution -> events.add(Map.of("type", "battle-resolved", "id", resolution.battle().id()));
        case WorldChange.EconomyChange economy -> {
          String type = economy.getClass().getSimpleName();
          events.add(includeEconomyDetails ? Map.of("type", type, "change", economy) : Map.of("type", type));
        }
        default -> events.add(Map.of("type", change.getClass().getSimpleName()));
      }
    }
    if (events.isEmpty()) events.add(Map.of("type", "clock"));
    return List.copyOf(events);
  }

  private static List<WorldChange.EconomyChange> economyChanges(List<WorldChange> changes) {
    return changes.stream().filter(WorldChange.EconomyChange.class::isInstance).map(WorldChange.EconomyChange.class::cast).toList();
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
    return save(actor, draftId, expectedVersion, requestId, Long.valueOf(tick), baseHash);
  }

  public synchronized Map<String, Object> save(String actor, String draftId, long expectedVersion,
      String requestId, Long tick, String baseHash) {
    requireText(actor, "actor"); requireText(requestId, "requestId"); requireText(baseHash, "baseHash");
    String requestHash = JsonCodec.sha256(JsonCodec.canonicalJson(List.of(draftId, expectedVersion, tick == null ? "event" : tick, baseHash)));
    var existing = store.receipt(actor, requestId);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(requestHash)) throw new TimelineException("REQUEST_ID_CONFLICT", "requestId 已用于其他请求");
      Draft pending = drafts.get(draftId);
      if (pending != null && pending.actor.equals(actor)) drafts.remove(draftId);
      var result = nodeSummary(requireNode(existing.get().node()));
      if (existing.get().nodes() != null) {
        result.put("nodes", existing.get().nodes().stream().map(this::requireNode).map(this::nodeSummary).toList());
      }
      result.put("replayedReceipt", true);
      return result;
    }
    Draft draft = draft(actor, draftId);
    requireVersion(draft, expectedVersion);
    TimelineNode saved;
    StaticWorldState state;
    List<TimelineNode> events = List.of();
    if (draft.eventTimeline) {
      if (tick != null) throw new TimelineException("EVENT_TICK_AUTOMATIC", "事件节点的 tick 由程序分配");
      if (draft.events == null) throw new TimelineException("EVENT_STEPS_REQUIRED", "请先应用至少一个事件时刻");
      var sequence = timeline.saveEvents(draft.copy, draft.events.steps(), actor, baseHash, requestId, requestHash);
      events = sequence.nodes();
      saved = events.getLast();
      state = sequence.state();
    } else {
      if (tick == null) throw new TimelineException("TIMELINE_TICK_INVALID", "旧式编辑需要提供 tick");
      NodeRef ref = new NodeRef(draft.copy.targetBranch(), tick);
      CommitReceipt receipt = new CommitReceipt(actor, requestId, requestHash, ref);
      saved = timeline.save(draft.copy, tick, actor, baseHash, receipt);
      state = draft.copy.currentState();
    }
    if (state.simulationTime() != null) clocks.put(saved.ref(), committedClock(state.simulationTime()));
    drafts.remove(draftId);
    Map<String, Object> result = new LinkedHashMap<>(nodeSummary(saved));
    if (!events.isEmpty()) result.put("nodes", events.stream().map(this::nodeSummary).toList());
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
    if (node.eventTime() != null) {
      value.put("eventTime", node.eventTime());
      value.put("events", eventSummary(WorldChangeSet.fromJson(node.changeSetJson()), false));
    }
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
    if (node.eventTime() != null) return node.eventTime();
    var changes = WorldChangeSet.fromJson(node.changeSetJson()).changes();
    for (var change : changes.reversed()) {
      if (change instanceof WorldChange.SetSimulationTime clock) return clock.time();
      if (change instanceof WorldChange.StartMarch march) return march.march().startedAt();
      if (change instanceof WorldChange.ResolveBattle battle) return battle.battle().endedAt();
      if (change instanceof WorldChange.ConfigureRailEconomy economy) return economy.time();
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
