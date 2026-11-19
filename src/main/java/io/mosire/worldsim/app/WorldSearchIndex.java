package io.mosire.worldsim.app;

import io.mosire.worldsim.address.AddressException;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.state.StaticWorldState;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Derived name search; returned results use the same stable IDs and addresses as lookups. */
final class WorldSearchIndex {
  static final Set<String> KINDS = Set.of("province", "state", "region", "country", "unit");
  private static final Pattern MARK = Pattern.compile("\\p{M}+");

  private record Entry(String kind, String id, String name, String context, List<String> terms) {
    String address(NodeRef node) {
      return switch (kind) {
        case "unit" -> WorldQueries.address(node, "orbat", "unit", id);
        case "country" -> WorldQueries.address(node, "country", id);
        default -> WorldQueries.address(node, "map", kind, id);
      };
    }
  }

  record Hit(String stableId, String kind, String address, String displayName, String context) {}
  private record Match(Hit hit, int score) {}

  private final List<Entry> entries = new ArrayList<>();

  WorldSearchIndex(StaticWorldState state) {
    var map = state.map();
    map.provinces().values().forEach(p -> {
      var region = p.regionId() == null ? null : map.strategicRegions().get(p.regionId());
      var local = p.stateId() == null ? null : map.states().get(p.stateId());
      String context = local != null ? local.name() : region == null ? null : region.name();
      var aliases = p.tags().stream().filter(tag -> tag.startsWith("alias:")).map(tag -> tag.substring(6)).toList();
      add("province", p.id(), p.name(), context, aliases);
    });
    map.states().values().forEach(s -> add("state", s.id(), s.name(), null, List.of()));
    map.strategicRegions().values().forEach(r -> add("region", r.id(), r.name(), null, List.of()));
    state.countries().countries().values().forEach(c -> add("country", c.id(), c.name(), null, c.aliases()));
    state.formations().units().values().forEach(u -> {
      var province = u.provinceId() == null ? null : map.provinces().get(u.provinceId());
      add("unit", u.id(), u.name(), province == null ? null : province.name(), List.of());
    });
  }

  private void add(String kind, String id, String name, String context, List<String> aliases) {
    List<String> terms = new ArrayList<>(List.of(fold(name), fold(id)));
    aliases.stream().map(WorldSearchIndex::fold).forEach(terms::add);
    entries.add(new Entry(kind, id, name, context, terms));
  }

  Map<String, Object> search(NodeRef node, String hash, String text, String kind, int offset, int limit) {
    String query = fold(text.strip());
    if (query.isEmpty()) throw new IllegalArgumentException("搜索词不得为空");
    List<Match> matches = new ArrayList<>();
    for (Entry entry : entries) {
      if (kind != null && !entry.kind().equals(kind)) continue;
      int score = 0;
      for (String term : entry.terms()) {
        if (term.equals(query)) score = 3;
        else if (term.startsWith(query)) score = Math.max(score, 2);
        else if (term.contains(query)) score = Math.max(score, 1);
      }
      if (score == 0) continue;
      try {
        var hit = new Hit(entry.kind() + ":" + entry.id(), entry.kind(), entry.address(node), entry.name(), entry.context());
        matches.add(new Match(hit, score));
      } catch (AddressException ignored) {
        // Old free-form map IDs may be unaddressable at this node.
      }
    }
    matches.sort(Comparator.comparingInt(Match::score).reversed()
        .thenComparing(m -> m.hit().displayName()).thenComparing(m -> m.hit().address()));
    int from = Math.min(offset, matches.size());
    int to = Math.min(from + limit, matches.size());
    return Map.of("node", node.label(), "stateHash", hash, "total", matches.size(), "offset", from,
        "matches", matches.subList(from, to).stream().map(Match::hit).toList());
  }

  private static String fold(String value) {
    return MARK.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
  }
}
