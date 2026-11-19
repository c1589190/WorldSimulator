package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.PolygonTopology;
import io.mosire.worldsim.orbat.MilOrgFormat;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.TimelineException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated intent that plans a domain change against an explicit working state. */
public sealed interface WorldCommand permits WorldCommand.PatchProvince, WorldCommand.PersistedChange,
    WorldCommand.RebuildAdjacencies {
  WorldChange plan(StaticWorldState state);

  static WorldCommand parse(Map<String, Object> command) {
    if (command == null) throw new TimelineException("COMMAND_INVALID", "命令不能为空");
    Object op = command.get("op");
    if (!(op instanceof String kind) || kind.isBlank()) throw new TimelineException("COMMAND_INVALID", "命令需要 op");
    if (kind.equals("rebuildAdjacencies")) {
      if (command.size() != 1) throw new TimelineException("COMMAND_INVALID", "rebuildAdjacencies 不接受其他字段");
      return new RebuildAdjacencies();
    }
    if (kind.equals("patchProvince")) {
      Set<String> allowed = Set.of("op", "id", "name", "kind", "terrain", "shape", "displayPoint", "stateId", "regionId", "tags");
      for (String key : command.keySet()) if (!allowed.contains(key)) throw new TimelineException("COMMAND_INVALID", "未知省字段: " + key);
      if (!(command.get("id") instanceof String id) || id.isBlank()) {
        throw new TimelineException("COMMAND_INVALID", "patchProvince 需要省 ID");
      }
      Map<String, JsonNode> fields = new LinkedHashMap<>();
      command.forEach((key, value) -> {
        if (!key.equals("op") && !key.equals("id")) fields.put(key, JsonCodec.mapper().valueToTree(value));
      });
      try { return new PatchProvince(new WorldChange.PatchProvince(id, fields)); }
      catch (IllegalArgumentException e) { throw new TimelineException("COMMAND_INVALID", "省修改字段不合法: " + id, e); }
    }
    try {
      if (kind.equals("importMilOrg")) {
        if (!command.keySet().equals(Set.of("op", "documentId", "role", "source", "milOrg"))) {
          throw new IllegalArgumentException("importMilOrg 需要 documentId、role、source 和 milOrg");
        }
        var document = MilOrgFormat.read(text(command, "documentId"), text(command, "role"),
            text(command, "source"), JsonCodec.mapper().valueToTree(command.get("milOrg")));
        return new PersistedChange(new WorldChange.PutOrbatDocument(document));
      }
      if (kind.equals("setTerritory") && !command.containsKey("assignment")) {
        throw new IllegalArgumentException("assignment 须显式指定，null 表示清除当前字段");
      }
      if (kind.equals("deployUnit") && !command.containsKey("provinceId")) {
        throw new IllegalArgumentException("provinceId 须显式指定，null 表示撤销部署");
      }
      return new PersistedChange(JsonCodec.mapper().convertValue(command, WorldChange.class));
    } catch (IllegalArgumentException e) {
      throw new TimelineException("COMMAND_INVALID", kind + " 命令不合法: " + e.getMessage(), e);
    }
  }

  private static String text(Map<String, Object> command, String field) {
    if (command.get(field) instanceof String value && !value.isBlank()) return value;
    throw new IllegalArgumentException("需要字符串字段: " + field);
  }

  record PatchProvince(WorldChange.PatchProvince change) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state) {
      if (!state.map().provinces().containsKey(change.provinceId())) {
        throw new TimelineException("COMMAND_PROVINCE_NOT_FOUND", "省不存在: " + change.provinceId());
      }
      return change;
    }
  }

  record PersistedChange(WorldChange change) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state) {
      return switch (change) {
        case WorldChange.PutUnit op -> new WorldChange.PutUnit(state.formations().bindIdeal(op.unit()));
        case WorldChange.SetTerritory op -> {
          for (String id : op.provinceIds()) {
            if (!state.map().provinces().containsKey(id)) {
              throw new TimelineException("COMMAND_PROVINCE_NOT_FOUND", "省不存在: " + id);
            }
          }
          yield change;
        }
        default -> change;
      };
    }
  }

  record RebuildAdjacencies() implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state) {
      List<Adjacency> edges = new ArrayList<>(PolygonTopology.deriveAdjacencies(state.map()));
      state.map().adjacencies().stream().filter(a -> a.kind().startsWith("special")).forEach(edges::add);
      return new WorldChange.ReplaceAdjacencies(edges);
    }
  }
}
