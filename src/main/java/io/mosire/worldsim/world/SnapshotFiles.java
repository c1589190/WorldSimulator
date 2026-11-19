package io.mosire.worldsim.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import io.mosire.worldsim.json.JsonCodec;
import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.EntityLink;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.ProvinceShape;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.Vec2;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.timeline.TimelineException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Versioned, readable snapshot chunks; content-addressed objects are shared between checkpoints. */
public final class SnapshotFiles {
  private static final int VERSION = 1;
  private static final int CHUNK_SIZE = 512;
  private final WorldDirectory world;
  private final String dataDirectory;

  public SnapshotFiles(WorldDirectory world, String dataDirectory) {
    this.world = world;
    this.dataDirectory = dataDirectory;
  }

  public record Manifest(int formatVersion, String stateId, String mapId, String mapName,
      double width, double height, String stateHash, Map<String, List<String>> chunks) {}

  public record ProvinceInfo(String id, String name, String kind, String terrain, Vec2 displayPoint,
      String stateId, String regionId, List<String> tags) {
    static ProvinceInfo from(Province p) {
      return new ProvinceInfo(p.id(), p.name(), p.kind(), p.terrain(), p.displayPoint(),
          p.stateId(), p.regionId(), p.tags());
    }

    Province withShape(ProvinceShape shape) {
      return new Province(id, name, kind, terrain, shape, displayPoint, stateId, regionId, tags);
    }
  }

  /** Returns the world-relative path of the immutable snapshot manifest. */
  public String write(StaticWorldState state) {
    WorldMap map = state.map();
    Map<String, List<String>> refs = new LinkedHashMap<>();
    List<Province> provinces = new ArrayList<>(map.provinces().values());
    List<String> metadata = new ArrayList<>();
    List<String> geometries = new ArrayList<>();
    for (int start = 0; start < provinces.size(); start += CHUNK_SIZE) {
      List<Province> chunk = provinces.subList(start, Math.min(start + CHUNK_SIZE, provinces.size()));
      metadata.add(writeObject("province", chunk.stream().map(ProvinceInfo::from).toList()));
      Map<String, ProvinceShape> shapes = new TreeMap<>();
      chunk.forEach(p -> shapes.put(p.id(), p.shape()));
      geometries.add(writeObject("geometry", shapes));
    }
    refs.put("province", List.copyOf(metadata));
    refs.put("geometry", List.copyOf(geometries));
    refs.put("state", chunks("state", new ArrayList<>(map.states().values())));
    refs.put("region", chunks("region", new ArrayList<>(map.strategicRegions().values())));
    refs.put("supplyHub", chunks("supplyHub", new ArrayList<>(map.supplyHubs().values())));
    refs.put("railway", chunks("railway", new ArrayList<>(map.railways().values())));
    refs.put("link", chunks("link", new ArrayList<>(map.entityLinks().values())));
    refs.put("adjacency", chunks("adjacency", map.adjacencies()));
    Manifest manifest = new Manifest(VERSION, state.id(), map.id(), map.name(), map.width(), map.height(),
        state.contentHash(), refs);
    String relative = dataDirectory + "/snapshots/" + UUID.randomUUID() + "/snapshot.json";
    DurableFiles.writeNew(world.resolve(relative), JsonCodec.prettyJson(manifest));
    return relative;
  }

  private <T> List<String> chunks(String category, List<T> values) {
    List<String> refs = new ArrayList<>();
    for (int start = 0; start < values.size(); start += CHUNK_SIZE) {
      refs.add(writeObject(category, values.subList(start, Math.min(start + CHUNK_SIZE, values.size()))));
    }
    return List.copyOf(refs);
  }

  private String writeObject(String category, Object value) {
    String json = JsonCodec.prettyJson(value);
    String hash = JsonCodec.sha256(json).substring("sha256:".length());
    String relative = dataDirectory + "/objects/" + category + "/" + hash + ".json";
    Path target = world.resolve(relative);
    if (Files.exists(target)) {
      verifyObject(relative, category);
      return relative;
    }
    String temp = dataDirectory + "/.staging/objects/" + UUID.randomUUID() + ".json";
    Path tempFile = world.resolve(temp);
    DurableFiles.writeNew(tempFile, json);
    DurableFiles.moveAtomic(tempFile, target, false);
    return relative;
  }

  public Manifest readManifest(String relative) {
    requireUnder(relative, "snapshots/");
    try {
      Manifest m = JsonCodec.fromJson(DurableFiles.read(world.resolve(relative)), Manifest.class);
      if (m.formatVersion() != VERSION || m.stateId() == null || m.mapId() == null
          || m.mapName() == null || m.stateHash() == null || m.chunks() == null) {
        throw new TimelineException("WORLD_SNAPSHOT_FORMAT", "不支持或不完整的快照: " + relative);
      }
      return m;
    } catch (TimelineException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TimelineException("WORLD_SNAPSHOT_FORMAT", "无法解析快照: " + relative, e);
    }
  }

  public StaticWorldState read(String relative) {
    Manifest m = readManifest(relative);
    List<ProvinceInfo> infos = readList(m, "province", ProvinceInfo.class);
    Map<String, ProvinceShape> geometry = new TreeMap<>();
    for (String ref : refs(m, "geometry")) {
      try {
        Map<String, ProvinceShape> piece = JsonCodec.mapper().readValue(
            verifyObject(ref, "geometry"), new TypeReference<Map<String, ProvinceShape>>() {});
        for (var entry : piece.entrySet()) {
          if (geometry.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
            throw new TimelineException("WORLD_SNAPSHOT_DUPLICATE", "重复省形: " + entry.getKey());
          }
        }
      } catch (IOException e) {
        throw new TimelineException("WORLD_SNAPSHOT_FORMAT", "无法解析几何对象: " + ref, e);
      }
    }
    Map<String, Province> provinces = new LinkedHashMap<>();
    for (ProvinceInfo info : infos) {
      ProvinceShape shape = geometry.remove(info.id());
      if (shape == null || provinces.putIfAbsent(info.id(), info.withShape(shape)) != null) {
        throw new TimelineException("WORLD_SNAPSHOT_PROVINCE", "省形缺失或重复: " + info.id());
      }
    }
    if (!geometry.isEmpty()) {
      throw new TimelineException("WORLD_SNAPSHOT_PROVINCE", "存在没有省信息的几何: " + geometry.keySet().iterator().next());
    }
    WorldMap map = new WorldMap(m.mapId(), m.mapName(), m.width(), m.height(), provinces,
        keyed(readList(m, "state", State.class), State::id),
        keyed(readList(m, "region", StrategicRegion.class), StrategicRegion::id),
        keyed(readList(m, "supplyHub", SupplyHub.class), SupplyHub::id),
        keyed(readList(m, "railway", Railway.class), Railway::id),
        keyed(readList(m, "link", EntityLink.class), EntityLink::key),
        readList(m, "adjacency", Adjacency.class));
    StaticWorldState state = new StaticWorldState(m.stateId(), map);
    if (!state.contentHash().equals(m.stateHash())) {
      throw new TimelineException("WORLD_SNAPSHOT_HASH_MISMATCH", "快照内容 hash 不一致: " + relative);
    }
    return state;
  }

  private <T> List<T> readList(Manifest manifest, String category, Class<T> type) {
    List<T> values = new ArrayList<>();
    JavaType listType = JsonCodec.mapper().getTypeFactory().constructCollectionType(List.class, type);
    for (String ref : refs(manifest, category)) {
      try {
        List<T> piece = JsonCodec.mapper().readValue(verifyObject(ref, category), listType);
        values.addAll(piece);
      } catch (IOException e) {
        throw new TimelineException("WORLD_SNAPSHOT_FORMAT", "无法解析 " + category + " 对象: " + ref, e);
      }
    }
    return List.copyOf(values);
  }

  private List<String> refs(Manifest manifest, String category) {
    List<String> refs = manifest.chunks().get(category);
    if (refs == null) throw new TimelineException("WORLD_SNAPSHOT_FORMAT", "快照缺少类别: " + category);
    return refs;
  }

  private String verifyObject(String relative, String category) {
    requireUnder(relative, "objects/" + category + "/");
    Path path = world.resolve(relative);
    String json = DurableFiles.read(path);
    String expectedName = JsonCodec.sha256(json).substring("sha256:".length()) + ".json";
    if (!path.getFileName().toString().equals(expectedName)) {
      throw new TimelineException("WORLD_OBJECT_HASH_MISMATCH", "世界对象内容已改变: " + relative);
    }
    return json;
  }

  private void requireUnder(String relative, String subdirectory) {
    if (relative == null || !relative.startsWith(dataDirectory + "/" + subdirectory)) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "快照引用不在预期目录: " + relative);
    }
    if (!world.resolve(relative).startsWith(world.resolve(dataDirectory + "/" + subdirectory))) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "快照引用越界: " + relative);
    }
  }

  private static <T> Map<String, T> keyed(List<T> values, Function<T, String> key) {
    Map<String, T> result = new LinkedHashMap<>();
    for (T value : values) {
      if (result.putIfAbsent(key.apply(value), value) != null) {
        throw new TimelineException("WORLD_SNAPSHOT_DUPLICATE", "快照含重复实体: " + key.apply(value));
      }
    }
    return result;
  }
}
