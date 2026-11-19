package io.mosire.worldsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.worldsim.address.NodeAddress;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.TimelineNode;
import io.mosire.worldsim.world.DurableFiles;
import io.mosire.worldsim.world.FileTimelineStore;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Optional migration evidence, tied to a saved source node and never treated as world state. */
final class WorldProvenance {
  record Result(JsonNode document, List<TimelineNode> descendants, String warning) {
    static Result none() { return new Result(null, List.of(), null); }
    static Result warning(String message) { return new Result(null, List.of(), message); }

    List<JsonNode> provinceEvidence(String id) {
      if (document == null) return List.of();
      List<JsonNode> result = new ArrayList<>();
      for (JsonNode item : document.path("fieldEvidence")) {
        if (!item.path("entityType").asText().equals("province") || !item.path("ids").isArray()) continue;
        boolean matches = false;
        for (JsonNode value : item.path("ids")) if (value.asText().equals(id)) matches = true;
        String field = item.path("field").asText();
        if (matches && evidenceStillApplies(id, field)) result.add(item);
      }
      return List.copyOf(result);
    }

    private boolean evidenceStillApplies(String id, String field) {
      Set<String> supported = Set.of("name", "kind", "terrain", "shape", "displayPoint", "stateId", "regionId", "tags");
      if (!supported.contains(field)) return descendants.isEmpty();
      for (TimelineNode node : descendants) {
        for (WorldChange change : WorldChangeSet.fromJson(node.changeSetJson()).changes()) {
          if (change instanceof WorldChange.PutProvince put && put.province().id().equals(id)) return false;
          if (change instanceof WorldChange.RemoveProvince remove && remove.provinceId().equals(id)) return false;
          if (change instanceof WorldChange.PatchProvince patch && patch.provinceId().equals(id)
              && patch.fields().containsKey(field)) return false;
        }
      }
      return true;
    }
  }

  static Result forNode(FileTimelineStore store, NodeRef target) {
    var path = store.world().resolve("provenance.json");
    if (!Files.exists(path)) return Result.none();
    try {
      JsonNode document = JsonCodec.mapper().readTree(DurableFiles.read(path));
      if (document.path("formatVersion").asInt() != 1 || !document.path("worldId").asText().equals(store.worldId())
          || !document.path("node").isTextual() || !document.path("stateHash").isTextual()
          || !document.path("source").isObject() || !document.path("fieldEvidence").isArray()) {
        return Result.warning("provenance.json 格式不完整，未使用来源提示");
      }
      NodeAddress sourceAddress = NodeAddress.parse(document.path("node").asText());
      if (!sourceAddress.path().isEmpty()) return Result.warning("来源提示必须引用保存节点");
      NodeRef source = sourceAddress.node();
      var sourceNode = store.find(source);
      if (sourceNode.isEmpty() || !sourceNode.get().stateHash().equals(document.path("stateHash").asText())) {
        return Result.warning("来源提示的节点或 hash 不匹配");
      }
      List<TimelineNode> pathToTarget = store.pathToRoot(target);
      for (int i = 0; i < pathToTarget.size(); i++) {
        if (pathToTarget.get(i).ref().equals(source)) {
          return new Result(document, List.copyOf(pathToTarget.subList(i + 1, pathToTarget.size())), null);
        }
      }
      return Result.none();
    } catch (Exception e) {
      return Result.warning("无法读取 provenance.json：" + e.getMessage());
    }
  }
}
