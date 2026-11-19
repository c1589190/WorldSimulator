package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.PolygonTopology;
import io.mosire.worldsim.orbat.MilOrgFormat;
import io.mosire.worldsim.state.Evidence;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.SimulationTime;
import io.mosire.worldsim.timeline.TimelineException;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Validated intent that plans a domain change against an explicit working state. */
public sealed interface WorldCommand permits WorldCommand.PatchProvince, WorldCommand.PersistedChange,
    WorldCommand.RebuildAdjacencies, WorldCommand.InitializeClock, WorldCommand.AdvanceTime,
    WorldCommand.StartMarch, WorldCommand.CancelMarch, WorldCommand.ResolveBattle, WorldCommand.SetTransitRight {
  WorldChange plan(StaticWorldState state, Context context);

  record Context(NodeRef node, String stateHash, Supplier<String> baseTime,
                 Function<WorldApplicationService.SavedMap, WorldGeography> geography) {
    String currentTime(StaticWorldState state) {
      return state.simulationTime() == null ? baseTime.get() : state.simulationTime();
    }

    String requireTime(StaticWorldState state) {
      String time = currentTime(state);
      if (time == null) throw new TimelineException("SIMULATION_TIME_REQUIRED", "请先设定剧本时间");
      return time;
    }

    void requireBase(NodeAddress address) {
      if (!address.node().equals(node)) throw new TimelineException("COMMAND_BASE_MISMATCH", "地址须属于草稿基点: " + address);
    }
  }

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
      if (kind.equals("setSimulationTime")) {
        requireFields(command, "op", "time");
        return new InitializeClock(SimulationTime.normalize(text(command, "time")));
      }
      if (kind.equals("advanceTime")) {
        requireFields(command, "op", "minutes");
        Object value = command.get("minutes");
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 1) {
          throw new IllegalArgumentException("minutes 须为正整数");
        }
        return new AdvanceTime(((Number) value).longValue());
      }
      if (kind.equals("startMarch")) {
        requireFields(command, "op", "address", "route");
        if (!(command.get("route") instanceof List<?> list)) throw new IllegalArgumentException("需要行军路线数组");
        var route = new ArrayList<String>();
        for (Object item : list) {
          if (!(item instanceof String address)) throw new IllegalArgumentException("路线需要省份地址");
          route.add(address);
        }
        return new StartMarch(NodeAddress.parse(text(command, "address")), List.copyOf(route));
      }
      if (kind.equals("cancelMarch")) {
        requireFields(command, "op", "address");
        return new CancelMarch(NodeAddress.parse(text(command, "address")));
      }
      if (kind.equals("resolveBattle")) {
        requireFields(command, "op", "battle");
        return new ResolveBattle(JsonCodec.mapper().convertValue(command.get("battle"), WorldBattle.Request.class));
      }
      if (kind.equals("setTransitRight")) {
        requireFields(command, "op", "grantorAddress", "guestAddress", "evidence");
        return new SetTransitRight(text(command, "grantorAddress"), text(command, "guestAddress"),
            JsonCodec.mapper().convertValue(command.get("evidence"), Evidence.class));
      }
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

  private static void requireFields(Map<String, Object> command, String... fields) {
    if (!command.keySet().equals(Set.of(fields))) throw new IllegalArgumentException("需要字段 " + List.of(fields));
  }

  private static String text(Map<String, Object> command, String field) {
    if (command.get(field) instanceof String value && !value.isBlank()) return value;
    throw new IllegalArgumentException("需要字符串字段: " + field);
  }

  record PatchProvince(WorldChange.PatchProvince change) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      if (!state.map().provinces().containsKey(change.provinceId())) {
        throw new TimelineException("COMMAND_PROVINCE_NOT_FOUND", "省不存在: " + change.provinceId());
      }
      return change;
    }
  }

  record PersistedChange(WorldChange change) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
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
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      List<Adjacency> edges = new ArrayList<>(PolygonTopology.deriveAdjacencies(state.map()));
      state.map().adjacencies().stream().filter(a -> a.kind().startsWith("special")).forEach(edges::add);
      return new WorldChange.ReplaceAdjacencies(edges);
    }
  }

  record InitializeClock(String time) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      String existing = context.currentTime(state);
      if (state.simulationTime() != null) throw new TimelineException("SIMULATION_TIME_SET", "已设剧本时间，可用 advanceTime 推进");
      if (existing != null && time.compareTo(existing) < 0) {
        throw new TimelineException("SIMULATION_TIME_REVERSED", "剧本时间早于基点日期");
      }
      return new WorldChange.SetSimulationTime(time);
    }
  }

  record AdvanceTime(long minutes) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      LocalDateTime current = LocalDateTime.parse(context.requireTime(state));
      try {
        return new WorldChange.SetSimulationTime(SimulationTime.format(current.plusMinutes(minutes)));
      } catch (DateTimeException | ArithmeticException | IllegalArgumentException e) {
        throw new TimelineException("SIMULATION_TIME_RANGE", "推进后的剧本时间超出范围", e);
      }
    }
  }

  record StartMarch(NodeAddress address, List<String> route) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      context.requireBase(address);
      String time = context.requireTime(state);
      var saved = new WorldApplicationService.SavedMap(context.node(), context.stateHash(), state);
      var march = WorldMovement.issue(saved, address, route, context.geography().apply(saved), time);
      return new WorldChange.StartMarch(address.path().getLast(), march);
    }
  }

  record CancelMarch(NodeAddress address) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      context.requireBase(address);
      var unit = WorldQueries.unit(state, address);
      return new WorldChange.CancelMarch(unit.id());
    }
  }

  record ResolveBattle(WorldBattle.Request battle) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      return new WorldChange.ResolveBattle(WorldBattle.resolve(state, context, battle));
    }
  }

  record SetTransitRight(String grantor, String guest, Evidence evidence) implements WorldCommand {
    @Override public WorldChange plan(StaticWorldState state, Context context) {
      return new WorldChange.SetTransitRight(WorldBattle.countryId(state, context, grantor),
          WorldBattle.countryId(state, context, guest), evidence);
    }
  }
}
