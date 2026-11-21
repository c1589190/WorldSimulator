package io.mosire.worldsim.app;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.info.InfoState;
import io.mosire.worldsim.info.TurnState;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.MapAddressResolver;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class WorldTurn {
  private WorldTurn() {}

  record DecisionSession(NodeAddress turn, String participantId, boolean legacy, boolean scoped) {}
  record InfoView(String address, List<String> subjectAddresses, InfoState.Entry entry) {}

  static BranchId inbox(NodeAddress turn, String participantId, boolean legacy) {
    var key = legacy ? List.of(turn.canonical(), participantId) : List.of("participant-v2", turn.canonical(), participantId);
    String digest = JsonCodec.sha256(JsonCodec.canonicalJson(key));
    return new BranchId("decision-" + digest.substring(7, 47));
  }

  static String turnId(NodeAddress address) {
    var path = address.path();
    if (path.size() != 2 || !path.getFirst().equals("turn")) throw new IllegalArgumentException("需要 <node>/turn/<id> 地址");
    return path.getLast();
  }

  static List<InfoView> info(StaticWorldState state, NodeRef node) {
    return state.intelligence().entries().values().stream()
        .sorted(Comparator.comparing(InfoState.Entry::key)).map(entry -> infoView(node, entry)).toList();
  }

  record DecisionInfo(String address, List<String> subjectAddresses, String title, String text, String type,
                      com.fasterxml.jackson.databind.JsonNode payload, io.mosire.worldsim.state.Evidence evidence) {}

  static List<DecisionInfo> decisionInfo(StaticWorldState state, NodeRef node, DecisionSession session) {
    return info(state, node).stream().filter(view -> (session.legacy() ? view.entry().audience()
        : view.entry().decisionAudience()).contains(session.participantId())).map(view -> {
      var entry = view.entry();
      return new DecisionInfo(view.address(), view.subjectAddresses(), entry.title(), entry.text(),
          entry.type(), entry.payload(), entry.evidence());
    }).toList();
  }

  static InfoView infoView(NodeRef node, InfoState.Entry entry) {
    return new InfoView(WorldQueries.address(node, "info", entry.pack(), entry.id()),
        entry.subjects().stream().map(path -> node.label() + "/" + path).toList(), entry);
  }

  static NodeAddress readable(NodeAddress address, DecisionSession session, TurnState.Brief brief) {
    var participant = brief.participant(session.participantId());
    return allowed(address, session, participant.readablePaths(), "地址不在本身份的读取权限内");
  }

  private static NodeAddress allowed(NodeAddress address, DecisionSession session, List<String> grants, String message) {
    if (!address.node().equals(session.turn().node()) || !grants.contains(String.join("/", address.path()))) {
      throw new TimelineException("DECISION_ACCESS_DENIED", message);
    }
    return address;
  }

  static Object entity(StaticWorldState state, NodeAddress address, String hash, String time) {
    var path = address.requireEntityPath().path();
    if (path.getFirst().equals("map")) {
      var entity = MapAddressResolver.resolve(state.map(), address);
      if (entity.value() instanceof Province p) {
        return Map.of("address", address.canonical(), "id", p.id(), "name", p.name(), "terrain", p.terrain(), "point", p.displayPoint());
      }
      return Map.of("address", address.canonical(), "stableId", entity.stableId(), "entity", entity.value());
    }
    if (path.getFirst().equals("country")) {
      return WorldCivic.profile(new WorldApplicationService.SavedMap(address.node(), hash, state), address, time);
    }
    if (path.getFirst().equals("orbat")) {
      if (path.size() == 3 && path.get(1).equals("command")) {
        var item = state.formations().commands().get(path.getLast());
        if (item == null) throw new TimelineException("ENTITY_NOT_FOUND", "参谋机关不存在");
        return item;
      }
      return WorldQueries.lookup(state, address, hash);
    }
    throw new IllegalArgumentException("决策实体授权支持地图、国家和编制地址。INFO 按受众提供");
  }

  record ContactDraft(String key, boolean retract, String title, String provinceId, String observedAt,
                      String suspectedUnit, String text, List<String> recipients, io.mosire.worldsim.state.Evidence evidence) {}

  static TurnState.Proposal proposal(DecisionSession session, TurnState.Brief brief,
                                     WorldApplicationService.SavedMap saved, String text, String rationale,
                                     List<String> targets, List<TurnState.Movement> movements, List<ContactDraft> contacts) {
    var paths = new ArrayList<String>();
    var participant = brief.participant(session.participantId());
    for (String textAddress : targets) {
      var address = allowed(NodeAddress.parse(textAddress), session, participant.proposalTargets(), "决策目标超出授权范围");
      paths.add(String.join("/", address.path()));
    }
    if (movements.isEmpty() && contacts.isEmpty()) return new TurnState.Proposal(text, rationale, paths);
    if (!Boolean.TRUE.equals(brief.scoped())) throw new TimelineException("DECISION_ACCESS_DENIED", "需要已开启的决策可见面");
    var view = new WorldPerspective(saved, participant, session.participantId());
    for (var movement : movements) {
      if (!view.commands(movement.unitId())) throw new TimelineException("DECISION_ACCESS_DENIED", "不能提交非指挥范围的调动");
      requireLand(saved.state(), movement.destinationId());
      movement.via().forEach(id -> requireLand(saved.state(), id));
    }
    var reports = new ArrayList<InfoState.ContactUpdate>();
    var pending = saved.state().intelligence();
    for (var draft : contacts) {
      NodeAddress.requireSegment(draft.key());
      String owner = session.participantId();
      String digest = JsonCodec.sha256(JsonCodec.canonicalJson(List.of(owner, draft.key())));
      String id = "dm-" + digest.substring(7, 47);
      InfoState.Contact item = null;
      if (!draft.retract()) {
        requireLand(saved.state(), draft.provinceId());
        var recipients = draft.recipients() == null ? List.<String>of() : draft.recipients();
        if (!participant.contactRecipients().containsAll(recipients)) {
          throw new TimelineException("DECISION_ACCESS_DENIED", "情报接收者超出授权范围");
        }
        for (String recipient : recipients) {
          String from = WorldPerspective.countryOf(saved.state(), participant.subject());
          String to = WorldPerspective.countryOf(saved.state(), brief.participant(recipient).subject());
          if (from == null || to == null || !from.equals(to) && saved.state().civics().treaties().values().stream()
              .noneMatch(treaty -> treaty.type().equals("alliance") && treaty.statusAt(saved.state().simulationTime()).equals("active")
                  && (treaty.first().equals(from) && treaty.second().equals(to) || treaty.first().equals(to) && treaty.second().equals(from)))) {
            throw new TimelineException("DECISION_ACCESS_DENIED", "情报接收者无同阵营关系");
          }
        }
        item = new InfoState.Contact(id, owner, draft.title(), draft.provinceId(), draft.observedAt(), draft.suspectedUnit(),
            draft.text(), recipients, draft.evidence());
      }
      var update = new InfoState.ContactUpdate(id, item);
      pending = pending.with(update, owner);
      reports.add(update);
    }
    return new TurnState.Proposal(text, rationale, paths, movements, reports);
  }

  private static void requireLand(StaticWorldState state, String id) {
    var province = id == null ? null : state.map().provinces().get(id);
    if (province == null || !province.kind().equals("land")) throw new IllegalArgumentException("需要本节点的有效陆省 ID");
  }
}
