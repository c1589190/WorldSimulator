package io.mosire.worldsim.info;

import io.mosire.worldsim.address.NodeAddress;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record TurnState(Map<String, Turn> turns) {
  public static final TurnState EMPTY = new TurnState(Map.of());

  public TurnState {
    turns = turns == null ? Map.of() : Map.copyOf(turns);
    turns.forEach((id, turn) -> {
      if (!id.equals(turn.brief().id())) throw new IllegalArgumentException("回合键与 ID 不一致");
    });
  }

  public record Subject(String kind, String id) {
    public Subject {
      if (!"country".equals(kind) && !"orbat/unit".equals(kind) && !"orbat/command".equals(kind)) throw new IllegalArgumentException("主管对象须为国家、参谋机关或部署单位");
      NodeAddress.requireSegment(id);
    }

    public String path() { return kind + "/" + id; }
  }

  public record Participant(String displayName, Subject subject, List<String> readablePaths, List<String> proposalTargets,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> contactRecipients) {
    public Participant(String displayName, Subject subject, List<String> readablePaths, List<String> proposalTargets) {
      this(displayName, subject, readablePaths, proposalTargets, null);
    }

    public Participant {
      if (displayName == null || displayName.isBlank() || subject == null) throw new IllegalArgumentException("决策人需要名称与主管对象");
      readablePaths = paths(readablePaths);
      proposalTargets = paths(proposalTargets);
      contactRecipients = InfoState.ids(contactRecipients);
    }
  }

  private static List<String> paths(List<String> paths) {
    if (paths == null) throw new IllegalArgumentException("请明确提供授权列表，空列表表示无权限");
    return paths.stream().map(InfoState::requirePath).distinct().sorted().toList();
  }

  public record Brief(String id, Map<String, List<String>> access, Map<String, Participant> participants, Boolean scoped) {
    public Brief(String id, Map<String, List<String>> access, Map<String, Participant> participants) {
      this(id, access, participants, null);
    }
    public Brief(String id, Map<String, List<String>> access) {
      this(id, access, null);
    }

    public Brief {
      NodeAddress.requireSegment(id);
      if ((access == null) == (participants == null)) throw new IllegalArgumentException("回合需要一套参与身份");
      if (access != null) {
        if (Boolean.TRUE.equals(scoped)) throw new IllegalArgumentException("可见面回合须声明 participants");
        if (access.isEmpty()) throw new IllegalArgumentException("回合至少需要一个参与国");
        var copy = new TreeMap<String, List<String>>();
        access.forEach((country, grants) -> {
          NodeAddress.requireSegment(country);
          copy.put(country, paths(grants));
        });
        access = Map.copyOf(copy);
      } else {
        if (participants.isEmpty()) throw new IllegalArgumentException("回合至少需要一个决策人");
        participants.keySet().forEach(NodeAddress::requireSegment);
        participants = Map.copyOf(participants);
        for (var entry : participants.entrySet()) {
          if ("gm".equals(entry.getKey()) || !participants.keySet().containsAll(entry.getValue().contactRecipients())) {
            throw new IllegalArgumentException("情报受众须为当前回合的参与者");
          }
        }
      }
    }

    public boolean legacy() { return access != null; }

    public java.util.Set<String> ids() { return legacy() ? access.keySet() : participants.keySet(); }

    public Participant participant(String id) {
      if (!legacy()) return participants.get(id);
      var grants = access.get(id);
      return grants == null ? null : new Participant(id, new Subject("country", id), grants, grants);
    }
  }

  public record Movement(String unitId, String destinationId, List<String> via) {
    public Movement {
      NodeAddress.requireSegment(unitId);
      NodeAddress.requireSegment(destinationId);
      via = via == null ? List.of() : List.copyOf(via);
      via.forEach(NodeAddress::requireSegment);
    }
  }

  public record Proposal(String text, String rationale, List<String> targets,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Movement> movements,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<InfoState.ContactUpdate> reports) {
    public Proposal(String text, String rationale, List<String> targets) { this(text, rationale, targets, null, null); }

    public Proposal {
      if (text == null || text.isBlank() || text.length() > 32000 || rationale == null || rationale.isBlank()
          || rationale.length() > 16000) throw new IllegalArgumentException("决策需要非空正文和理由");
      movements = movements == null ? List.of() : List.copyOf(movements);
      reports = reports == null ? List.of() : List.copyOf(reports);
      targets = targets == null ? List.of() : targets.stream().map(InfoState::requirePath).distinct().toList();
    }
  }

  public static final List<String> DISPOSITIONS = List.of("accepted", "modified", "rejected", "deferred");

  public record Ruling(String id, String title, String text, String decisionMakerId, String disposition) {
    public Ruling {
      NodeAddress.requireSegment(id);
      if (title == null || title.isBlank() || title.length() > 240 || text == null || text.isBlank() || text.length() > 32000) {
        throw new IllegalArgumentException("裁定需要标题和正文");
      }
      if (decisionMakerId == null) {
        if (disposition != null) throw new IllegalArgumentException("只有提案批示可以记录处理结果");
      } else {
        NodeAddress.requireSegment(decisionMakerId);
        if (disposition == null || !DISPOSITIONS.contains(disposition)) throw new IllegalArgumentException("提案批示需要有效的处理结果");
      }
    }
  }

  public record Turn(Brief brief, Map<String, Proposal> proposals, String resolution, List<Ruling> rulings) {
    public Turn(Brief brief, Map<String, Proposal> proposals, String resolution) {
      this(brief, proposals, resolution, null);
    }

    public Turn {
      if (brief == null) throw new IllegalArgumentException("缺少回合说明");
      proposals = proposals == null ? Map.of() : Map.copyOf(proposals);
      proposals.forEach((participant, proposal) -> validateProposal(brief, participant, proposal));
      if (resolution != null && (resolution.isBlank() || !proposals.keySet().equals(brief.ids()))) {
        throw new IllegalArgumentException("裁决需要全部参与者的决策与说明");
      }
      if (rulings != null) {
        if (resolution == null) throw new IllegalArgumentException("裁定须随回合结算发布");
        rulings = List.copyOf(rulings);
        var ids = new java.util.HashSet<String>();
        for (Ruling item : rulings) {
          if (!ids.add(item.id())) throw new IllegalArgumentException("裁定 ID 重复: " + item.id());
          if (item.decisionMakerId() != null && !proposals.containsKey(item.decisionMakerId())) {
            throw new IllegalArgumentException("裁定关联的提案未归档: " + item.decisionMakerId());
          }
        }
      }
    }
  }

  public TurnState open(Brief brief) {
    if (turns.containsKey(brief.id()) || turns.values().stream().anyMatch(turn -> turn.resolution() == null)) {
      throw new IllegalArgumentException("回合 ID 重复或已有开放回合");
    }
    return with(new Turn(brief, Map.of(), null));
  }

  public TurnState submit(String id, String participant, Proposal proposal) {
    var turn = requireOpen(id);
    if (!turn.proposals().isEmpty()) throw new IllegalArgumentException("该收件分支已有决策");
    return with(new Turn(turn.brief(), Map.of(participant, proposal), null));
  }

  public TurnState resolve(String id, Map<String, Proposal> results, String resolution, List<Ruling> rulings) {
    var turn = requireOpen(id);
    if (resolution == null || !turn.proposals().isEmpty()) throw new IllegalArgumentException("裁决须在正式分支记录");
    return with(new Turn(turn.brief(), results, resolution, rulings));
  }

  public Turn requireOpen(String id) {
    var turn = turns.get(id);
    if (turn == null || turn.resolution() != null) throw new IllegalArgumentException("没有此开放回合: " + id);
    return turn;
  }

  public static void validateProposal(Brief brief, String participant, Proposal proposal) {
    var grant = brief.participant(participant);
    if (grant == null || proposal == null || !grant.proposalTargets().containsAll(proposal.targets())
        || !Boolean.TRUE.equals(brief.scoped()) && (!proposal.movements().isEmpty() || !proposal.reports().isEmpty())) {
      throw new IllegalArgumentException("决策目标超出授权范围");
    }
    for (var report : proposal.reports()) {
      var item = report.contact();
      if (item != null && (!participant.equals(item.owner()) || !grant.contactRecipients().containsAll(item.recipients()))) {
        throw new IllegalArgumentException("情报投递超出授权范围");
      }
    }
  }

  private TurnState with(Turn turn) {
    var next = new TreeMap<>(turns);
    next.put(turn.brief().id(), turn);
    return new TurnState(next);
  }
}
