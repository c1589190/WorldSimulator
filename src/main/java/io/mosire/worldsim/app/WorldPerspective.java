package io.mosire.worldsim.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.info.InfoState;
import io.mosire.worldsim.info.TurnState;
import io.mosire.worldsim.map.MapFeature;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Server-bound projection. Use the same visibility checks for direct reads and discovery. */
final class WorldPerspective {
  private final WorldApplicationService.SavedMap saved;
  private final TurnState.Participant participant;
  private final String identity;
  private final String country;

  WorldPerspective(WorldApplicationService.SavedMap saved, TurnState.Participant participant, String identity) {
    this.saved = saved;
    this.participant = participant;
    this.identity = identity;
    this.country = countryOf(saved.state(), participant.subject());
  }

  private StaticWorldState state() { return saved.state(); }

  static String countryOf(StaticWorldState state, TurnState.Subject subject) {
    return switch (subject.kind()) {
      case "country" -> subject.id();
      case "orbat/command" -> state.formations().commands().get(subject.id()).countryId();
      case "orbat/unit" -> state.formations().units().get(subject.id()).countryId();
      default -> throw new IllegalArgumentException("未知决策主体");
    };
  }

  boolean commands(String unitId) {
    var subject = participant.subject();
    if (subject.kind().equals("orbat/unit")) return subject.id().equals(unitId);
    if (!subject.kind().equals("orbat/command")) return false;
    var headquarters = state().formations().commands().get(subject.id());
    return headquarters.unitIds().contains(unitId) || state().formations().commands().values().stream().anyMatch(child ->
        headquarters.id().equals(child.parentId()) && child.unitIds().contains(unitId));
  }

  boolean canRead(NodeAddress address) {
    if (!saved.node().equals(address.node())) return false;
    var path = address.path();
    if (path.size() == 2 && path.getFirst().equals("country")) return state().countries().countries().containsKey(path.getLast());
    if (path.size() == 3 && path.getFirst().equals("info")) {
      return switch (path.get(1)) {
        case "document" -> canRead(state().intelligence().documents().get(path.getLast()));
        case "contact" -> canRead(state().intelligence().contacts().get(path.getLast()));
        default -> false;
      };
    }
    if (path.size() == 3 && path.getFirst().equals("map")
        && (path.get(1).equals("province") || path.get(1).equals("feature"))) return true;
    if (path.size() == 3 && path.getFirst().equals("orbat") && path.get(1).equals("unit") && commands(path.getLast())) return true;
    String key = String.join("/", path);
    return participant.subject().path().equals(key) || participant.readablePaths().contains(key);
  }

  private boolean canRead(InfoState.Document item) {
    return item != null && switch (item.scope()) {
      case "public" -> true;
      case "shared" -> country != null && item.countries().contains(country) || ownCore(item);
      case "core" -> ownCore(item);
      default -> false;
    };
  }

  private boolean ownCore(InfoState.Document item) {
    return participant.subject().kind().equals("country") && item.countryId().equals(country);
  }

  private boolean canRead(InfoState.Contact item) {
    return item != null && (identity.equals(item.owner()) || item.recipients().contains(identity));
  }

  Object lookup(NodeAddress address, String time) {
    if (!canRead(address)) throw new TimelineException("DECISION_ACCESS_DENIED", "地址不在本身份的读取权限内");
    var path = address.path();
    if (path.getFirst().equals("country")) {
      var item = state().countries().countries().get(path.getLast());
      return Map.of("address", address.canonical(), "id", item.id(), "name", item.name(),
          "documents", documents().stream().filter(doc -> doc.countryId().equals(item.id())).toList());
    }
    if (path.getFirst().equals("info")) {
      return path.get(1).equals("document") ? documentView(state().intelligence().documents().get(path.getLast()))
          : contactView(state().intelligence().contacts().get(path.getLast()));
    }
    return WorldTurn.entity(state(), address, saved.stateHash(), time);
  }

  record DocumentView(String id, String countryId, String title, String text, String scope,
                      io.mosire.worldsim.state.Evidence evidence) {}
  record ContactView(String id, String owner, String title, String provinceId, String observedAt, String suspectedUnit,
                     String text, io.mosire.worldsim.state.Evidence evidence) {}

  private static DocumentView documentView(InfoState.Document item) {
    return new DocumentView(item.id(), item.countryId(), item.title(), item.text(), item.scope(), item.evidence());
  }

  private static ContactView contactView(InfoState.Contact item) {
    return new ContactView(item.id(), item.owner(), item.title(), item.provinceId(), item.observedAt(),
        item.suspectedUnit(), item.text(), item.evidence());
  }

  List<DocumentView> documents() {
    return state().intelligence().documents().values().stream().filter(this::canRead)
        .sorted(Comparator.comparing(InfoState.Document::id)).map(WorldPerspective::documentView).toList();
  }

  List<Marker> markers() {
    return java.util.stream.Stream.concat(markersForGM(saved).stream().filter(marker -> canRead(NodeAddress.parse(marker.address()))),
        WorldQueries.unitMarkers(state(), saved.node(), null).stream()
            .filter(unit -> canRead(NodeAddress.parse(unit.address())))
            .map(unit -> new Marker("unit", unit.address(), unit.name(), unit.provinceAddress(), unit.point(),
                unit.type(), unit.echelon(), unit.countryId(), unit.countryColor()))).toList();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Marker(String kind, String address, String name, String provinceAddress, Vec2 point,
                String type, String echelon, String countryId, String countryColor) {}

  record UnitSight(String id, String name, List<String> units, List<String> contacts) {}

  UnitSight unitSight() {
    var units = state().formations().units().values().stream().filter(unit -> unit.provinceId() != null)
        .map(unit -> new NodeAddress(saved.node(), List.of("orbat", "unit", unit.id())))
        .filter(this::canRead).map(NodeAddress::canonical).sorted().toList();
    var contacts = state().intelligence().contacts().values().stream().filter(this::canRead)
        .map(item -> WorldQueries.address(saved.node(), "info", "contact", item.id())).sorted().toList();
    return new UnitSight(identity, participant.displayName(), units, contacts);
  }

  static List<Marker> markersForGM(WorldApplicationService.SavedMap saved) {
    var features = saved.map().features().values().stream().sorted(Comparator.comparing(MapFeature::id))
        .map(feature -> marker(saved, feature.kind(), "map/feature/", feature.id(), feature.name(), feature.provinceId()));
    var contacts = saved.state().intelligence().contacts().values().stream().sorted(Comparator.comparing(InfoState.Contact::id))
        .map(contact -> marker(saved, "contact", "info/contact/", contact.id(), contact.title(), contact.provinceId()));
    return java.util.stream.Stream.concat(features, contacts).toList();
  }

  private static Marker marker(WorldApplicationService.SavedMap saved, String kind, String prefix, String id,
                               String name, String provinceId) {
    Province province = Objects.requireNonNull(saved.map().provinces().get(provinceId));
    String node = saved.node().label();
    return new Marker(kind, node + "/" + prefix + id, name, node + "/map/province/" + provinceId,
        province.displayPoint(), null, null, null, null);
  }
}
