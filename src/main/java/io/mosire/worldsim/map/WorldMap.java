package io.mosire.worldsim.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 静态地图状态：原生 polygon 省 + 州 + 战略区域 + 补给中心 + 省邻接。
 *
 * <p>这是“静态状态”的首版容器。它不包含 owner/controller、单位、资源产出、天气等动态状态；
 * 这些将在后续时间线 ChangeSet 的扩展模块中处理。
 *
 * <p>集合一律按 key/规范排序后冻结，保证同一内容序列化/哈希稳定。
 */
public record WorldMap(
    String id,
    String name,
    double width,
    double height,
    Map<String, Province> provinces,
    Map<String, State> states,
    Map<String, StrategicRegion> strategicRegions,
    Map<String, SupplyHub> supplyHubs,
    List<Adjacency> adjacencies) {

  public WorldMap {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("WorldMap.id 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("WorldMap.name 不得为空白: " + id);
    }
    if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0) {
      throw new IllegalArgumentException("WorldMap 宽高必须是正有限数: " + width + " x " + height);
    }
    provinces = sortedById(provinces, "provinces");
    states = sortedById(states, "states");
    strategicRegions = sortedById(strategicRegions, "strategicRegions");
    supplyHubs = sortedById(supplyHubs, "supplyHubs");

    List<Adjacency> normalized = new ArrayList<>(Objects.requireNonNull(adjacencies, "adjacencies"));
    for (Adjacency adjacency : normalized) {
      Objects.requireNonNull(adjacency, "adjacency");
    }
    normalized.sort(Comparator.comparing(Adjacency::a).thenComparing(Adjacency::b).thenComparing(Adjacency::kind));
    adjacencies = List.copyOf(normalized);
  }

  private static <T> Map<String, T> sortedById(Map<String, T> source, String field) {
    Objects.requireNonNull(source, field);
    TreeMap<String, T> sorted = new TreeMap<>();
    for (Map.Entry<String, T> entry : source.entrySet()) {
      String id = entry.getKey();
      T value = entry.getValue();
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException(field + " 的 key 不得为空白");
      }
      Objects.requireNonNull(value, field + " 的值");
      sorted.put(id, value);
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
  }

  public static Builder builder(String id, String name, double width, double height) {
    return new Builder(id, name, width, height);
  }

  /** 便于测试/构造器使用的可变 builder；build 之后全部冻结。 */
  public static final class Builder {
    private final String id;
    private final String name;
    private final double width;
    private final double height;
    private final Map<String, Province> provinces = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, StrategicRegion> regions = new LinkedHashMap<>();
    private final Map<String, SupplyHub> supplyHubs = new LinkedHashMap<>();
    private final List<Adjacency> adjacencies = new ArrayList<>();

    private Builder(String id, String name, double width, double height) {
      this.id = id;
      this.name = name;
      this.width = width;
      this.height = height;
    }

    public Builder province(Province value) {
      provinces.put(value.id(), value);
      return this;
    }

    public Builder state(State value) {
      states.put(value.id(), value);
      return this;
    }

    public Builder strategicRegion(StrategicRegion value) {
      regions.put(value.id(), value);
      return this;
    }

    public Builder supplyHub(SupplyHub value) {
      supplyHubs.put(value.id(), value);
      return this;
    }

    public Builder adjacency(Adjacency value) {
      adjacencies.add(value);
      return this;
    }

    public Builder adjacency(String a, String b, String kind, double borderLength) {
      return adjacency(new Adjacency(a, b, kind, borderLength));
    }

    /** 以当前 provinces 的 polygon 共享边界推导并整体替换邻接。 */
    public Builder deriveAdjacencies() {
      WorldMap base = build();
      adjacencies.clear();
      adjacencies.addAll(PolygonTopology.deriveAdjacencies(base));
      return this;
    }

    public WorldMap build() {
      return new WorldMap(id, name, width, height, provinces, states, regions, supplyHubs, adjacencies);
    }
  }
}
