package io.mosire.worldsim.importer;

import io.mosire.worldsim.map.Adjacency;
import io.mosire.worldsim.map.Province;
import io.mosire.worldsim.map.Railway;
import io.mosire.worldsim.map.State;
import io.mosire.worldsim.map.StrategicRegion;
import io.mosire.worldsim.map.SupplyHub;
import io.mosire.worldsim.map.ValidationIssue;
import io.mosire.worldsim.map.WorldMap;
import io.mosire.worldsim.map.WorldMapValidator;
import io.mosire.worldsim.state.StaticWorldState;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把 HOI4 社区地图目录编译成 WorldSimulator 的原生 polygon 静态世界。
 *
 * <p>当前适配 Mappa Mundi GitHub 版：
 * {@code map/provinces.bmp + map/definition.csv + history/states + map/strategicregions
 * + localisation + map/supply_nodes.txt}。不读取用户本机 HOI4 安装。
 *
 * <p>raw 资产不进入仓库；调用方应把下载目录放在 gitignored 的 {@code runtime/imports/**}。
 */
public final class Hoi4CommunityMapImporter {
  private static final Pattern ID_FIELD = Pattern.compile("(?m)^\\s*id\\s*=\\s*(\\d+)");
  private static final Pattern NAME_FIELD = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern PROVINCES_FIELD =
      Pattern.compile("(?s)\\bprovinces\\s*=*\\s*\\{(.*?)}");
  private static final Pattern INT_TOKEN = Pattern.compile("\\d+");
  private static final Pattern LOCALISATION =
      Pattern.compile("^\\s*([A-Za-z0-9_.-]+):\\d*\\s+\"(.*)\"\\s*$");
  private static final String UNASSIGNED_STATE = "unassigned-state";
  private static final String UNASSIGNED_REGION = "unassigned-region";
  private static final int WATER_HEIGHT_MAX = 95;

  public ImportResult importWorld(Path sourceDirectory) {
    Path root = Objects.requireNonNull(sourceDirectory, "sourceDirectory")
        .toAbsolutePath().normalize();
    Path provinceBitmap = root.resolve("map/provinces.bmp");
    Path definitionFile = root.resolve("map/definition.csv");
    Path heightmapFile = root.resolve("map/heightmap.bmp");
    Path statesDirectory = root.resolve("history/states");
    Path regionsDirectory = root.resolve("map/strategicregions");
    requireFile(provinceBitmap);
    requireFile(definitionFile);
    requireDirectory(statesDirectory);
    requireDirectory(regionsDirectory);

    List<String> warnings = new ArrayList<>();
    DefinitionTable definitions = parseDefinitions(definitionFile);
    Bmp24Reader.Raster raster;
    try {
      raster = Bmp24Reader.read(provinceBitmap);
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_BMP_READ_FAILED",
          "读取 provinces.bmp 失败: " + provinceBitmap, e);
    }

    int[] provinceIds = new int[raster.rgb().length];
    int[] pixelCounts = new int[definitions.byId().length];
    for (int index = 0; index < raster.rgb().length; index++) {
      int color = raster.rgb()[index] & 0xFFFFFF;
      int provinceId = definitions.colorToId()[color];
      if (provinceId < 0) {
        int x = index % raster.width();
        int y = index / raster.width();
        throw new Hoi4ImportException("HOI4_UNKNOWN_PROVINCE_COLOR",
            String.format("bitmap 颜色 (r=%d,g=%d,b=%d) 不在 definition.csv 中 @ (%d,%d)",
                (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, x, y));
      }
      provinceIds[index] = provinceId;
      pixelCounts[provinceId]++;
    }

    Set<Integer> usedProvinceIds = new TreeSet<>();
    for (int id = 0; id < pixelCounts.length; id++) {
      if (pixelCounts[id] > 0) {
        usedProvinceIds.add(id);
      }
    }
    if (usedProvinceIds.isEmpty()) {
      throw new Hoi4ImportException("HOI4_RASTER_EMPTY", "provinces.bmp 没有任何已知省颜色");
    }

    RasterProvincePolygonizer.Result polygons =
        RasterProvincePolygonizer.polygonize(raster.width(), raster.height(), provinceIds, usedProvinceIds);
    ProvinceKinds kinds = inferProvinceKinds(heightmapFile, definitions.byId(), pixelCounts,
        raster.width(), raster.height(), provinceIds, usedProvinceIds, warnings);

    Map<String, String> localisation = parseLocalisation(root.resolve("localisation"));
    ParsedMapData stateData = parseStates(statesDirectory, usedProvinceIds, kinds.kinds(), warnings);
    ParsedRegionData regionData = parseRegions(regionsDirectory, usedProvinceIds, warnings);
    assignFallbacks(usedProvinceIds, kinds.kinds(), stateData, regionData, warnings);
    Map<Integer, String> provinceNames = provinceNames(usedProvinceIds, localisation);
    Map<String, State> states = buildStates(usedProvinceIds, kinds.kinds(), stateData, localisation);
    Map<String, StrategicRegion> regions = buildRegions(regionData, localisation);
    Map<String, SupplyHub> supplyHubs = parseSupplyHubs(root.resolve("map/supply_nodes.txt"),
        usedProvinceIds, kinds.kinds(), warnings);
    Map<String, Railway> railways = parseRailways(root.resolve("map/railways.txt"),
        usedProvinceIds, kinds.kinds(), warnings);

    String sourceName = root.getFileName() == null ? "hoi4-map" : root.getFileName().toString();
    String worldId = sourceName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    if (worldId.isBlank()) {
      worldId = "hoi4-map";
    }
    WorldMap.Builder map = WorldMap.builder(worldId, sourceName, raster.width(), raster.height());
    for (int id : usedProvinceIds) {
      Definition definition = definitions.byId()[id];
      if (definition == null) {
        throw new Hoi4ImportException("HOI4_DEFINITION_MISSING", "使用中的省 id 无 definition: " + id);
      }
      String provinceId = Integer.toString(id);
      String kind = kinds.kinds()[id];
      Integer assignedState = stateData.provinceState().get(id);
      String stateId = kind.equals("sea")
          ? null
          : (assignedState == null ? UNASSIGNED_STATE : Integer.toString(assignedState));
      String regionId = regionData.provinceRegion().get(id);
      if (regionId == null) {
        regionId = UNASSIGNED_REGION;
      }
      map.province(new Province(
          provinceId,
          provinceNames.getOrDefault(id, "Province " + id),
          kind,
          definition.terrain(),
          polygons.shapes().get(id),
          polygons.displayPoints().get(id),
          stateId,
          regionId,
          List.of("hoi4", worldId, "hoi4-province:" + id)));
    }
    states.values().forEach(map::state);
    regions.values().forEach(map::strategicRegion);
    supplyHubs.values().forEach(map::supplyHub);
    railways.values().forEach(map::railway);

    List<Adjacency> adjacencies = new ArrayList<>();
    for (Map.Entry<Long, Integer> entry : polygons.borderLengths().entrySet()) {
      int a = RasterProvincePolygonizer.borderA(entry.getKey());
      int b = RasterProvincePolygonizer.borderB(entry.getKey());
      if (definitions.byId()[a] == null || definitions.byId()[b] == null) {
        continue;
      }
      adjacencies.add(new Adjacency(Integer.toString(a), Integer.toString(b),
          adjacencyKind(kinds.kinds()[a], kinds.kinds()[b]), entry.getValue().doubleValue()));
    }
    List<Adjacency> specialAdjacencies = parseSpecialAdjacencies(
        root.resolve("map/adjacencies.csv"), usedProvinceIds, adjacencies, warnings);
    adjacencies.addAll(specialAdjacencies);
    adjacencies.forEach(map::adjacency);
    WorldMap worldMap = map.build();
    List<ValidationIssue> issues = WorldMapValidator.validate(worldMap);
    if (!issues.isEmpty()) {
      throw new Hoi4ImportException("HOI4_MAP_INVALID", formatIssues(issues));
    }

    StaticWorldState state = new StaticWorldState(worldMap.id(), worldMap);
    ImportStats stats = new ImportStats(
        raster.width(),
        raster.height(),
        definitions.byId().length - 1,
        usedProvinceIds.size(),
        states.size(),
        regions.size(),
        adjacencies.size(),
        supplyHubs.size(),
        railways.size(),
        polygons.ringsTraced(),
        polygons.repaired(),
        kinds.inferredSeaProvinces(),
        warnings.size());
    return new ImportResult(state, stats, List.copyOf(warnings));
  }

  private static void assignFallbacks(Set<Integer> usedProvinceIds, String[] kinds,
      ParsedMapData stateData, ParsedRegionData regionData, List<String> warnings) {
    int missingStates = 0;
    int missingRegions = 0;
    for (int id : usedProvinceIds) {
      if (!kinds[id].equals("sea") && !stateData.provinceState().containsKey(id)) {
        missingStates++;
      }
      if (!regionData.provinceRegion().containsKey(id)) {
        regionData.provinceRegion().put(id, UNASSIGNED_REGION);
        missingRegions++;
      }
    }
    stateData.setUnassignedState(missingStates > 0);
    if (missingStates > 0) {
      warnings.add(missingStates + " 个省没有 state 归属，归入 " + UNASSIGNED_STATE);
    }
    regionData.setUnassignedRegion(missingRegions > 0);
    if (missingRegions > 0) {
      warnings.add(missingRegions + " 个省没有 strategic region 归属，归入 " + UNASSIGNED_REGION);
    }
  }

  private static Map<String, State> buildStates(Set<Integer> usedProvinceIds, String[] kinds,
      ParsedMapData data, Map<String, String> localisation) {
    Map<String, List<String>> provinceIdsByState = new LinkedHashMap<>();
    List<String> unassigned = new ArrayList<>();
    for (Map.Entry<Integer, Integer> entry : data.provinceState().entrySet()) {
      if (entry.getValue() != null) {
        provinceIdsByState.computeIfAbsent(Integer.toString(entry.getValue()), ignored -> new ArrayList<>())
            .add(Integer.toString(entry.getKey()));
      }
    }
    for (int id : usedProvinceIds) {
      if (!kinds[id].equals("sea") && !data.provinceState().containsKey(id)) {
        unassigned.add(Integer.toString(id));
      }
    }
    if (!unassigned.isEmpty()) {
      provinceIdsByState.put(UNASSIGNED_STATE, unassigned);
    }
    Map<String, State> states = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : provinceIdsByState.entrySet()) {
      String id = entry.getKey();
      String name;
      if (id.equals(UNASSIGNED_STATE)) {
        name = "Unassigned Provinces";
      } else {
        int numericId = Integer.parseInt(id);
        String key = data.stateNameKeys().get(numericId);
        name = key == null ? "State " + id : localisation.getOrDefault(key, "State " + id);
      }
      states.put(id, new State(id, name, entry.getValue()));
    }
    return states;
  }

  private static Map<String, StrategicRegion> buildRegions(ParsedRegionData data,
      Map<String, String> localisation) {
    Map<String, List<String>> provinceIdsByRegion = new LinkedHashMap<>();
    for (Map.Entry<Integer, String> entry : data.provinceRegion().entrySet()) {
      provinceIdsByRegion.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
          .add(Integer.toString(entry.getKey()));
    }
    Map<String, StrategicRegion> regions = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : provinceIdsByRegion.entrySet()) {
      String id = entry.getKey();
      String name;
      if (id.equals(UNASSIGNED_REGION)) {
        name = "Unassigned Region";
      } else {
        int numericId = Integer.parseInt(id);
        String key = data.regionNameKeys().get(numericId);
        name = key == null ? "Region " + id : localisation.getOrDefault(key, "Region " + id);
      }
      regions.put(id, new StrategicRegion(id, name, entry.getValue()));
    }
    return regions;
  }

  private static Map<Integer, String> provinceNames(Set<Integer> used, Map<String, String> localisation) {
    Map<Integer, String> names = new HashMap<>();
    for (int id : used) {
      names.put(id, localisation.getOrDefault("VICTORY_POINTS_" + id, "Province " + id));
    }
    return names;
  }

  private static Map<String, SupplyHub> parseSupplyHubs(Path file, Set<Integer> used,
      String[] kinds, List<String> warnings) {
    if (!Files.isRegularFile(file)) {
      warnings.add("缺少 map/supply_nodes.txt，导入零个 supply hub");
      return Map.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_SUPPLY_READ_FAILED", "读取 supply_nodes.txt 失败", e);
    }
    List<Integer> tokens = ints(text);
    Map<String, SupplyHub> hubs = new LinkedHashMap<>();
    for (int i = 0; i + 1 < tokens.size(); i += 2) {
      int marker = tokens.get(i);
      int provinceId = tokens.get(i + 1);
      if (marker != 1) {
        warnings.add("supply_nodes 非 1 标记，跳过 pair: " + marker + " " + provinceId);
        continue;
      }
      if (!used.contains(provinceId) || provinceId >= kinds.length
          || !kinds[provinceId].equals("land")) {
        warnings.add("supply node 引用无效 land province: " + provinceId);
        continue;
      }
      String hubId = "supply-" + provinceId;
      hubs.putIfAbsent(hubId, new SupplyHub(hubId, Integer.toString(provinceId), 1));
    }
    return hubs;
  }

  private static List<Adjacency> parseSpecialAdjacencies(Path file, Set<Integer> used,
      List<Adjacency> existing, List<String> warnings) {
    if (!Files.isRegularFile(file)) {
      warnings.add("缺少 map/adjacencies.csv，跳过特殊邻接");
      return List.of();
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_ADJACENCY_READ_FAILED", "读取 adjacencies.csv 失败", e);
    }
    Set<String> seen = new java.util.HashSet<>();
    for (Adjacency adjacency : existing) {
      seen.add(adjacency.a() + "|" + adjacency.b() + "|" + adjacency.kind());
    }
    List<Adjacency> special = new ArrayList<>();
    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
      String line = lines.get(lineIndex).strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("From;")) {
        continue;
      }
      String[] fields = line.split(";", -1);
      if (fields.length < 3) {
        warnings.add("adjacency 行列数不足，跳过 line=" + (lineIndex + 1));
        continue;
      }
      int from;
      int to;
      try {
        from = Integer.parseInt(fields[0].trim());
        to = Integer.parseInt(fields[1].trim());
      } catch (NumberFormatException e) {
        warnings.add("adjacency 行 ID 非整数，跳过 line=" + (lineIndex + 1));
        continue;
      }
      if (from == to || !used.contains(from) || !used.contains(to)) {
        continue;
      }
      String type = fields[2].trim();
      String kind = type.isEmpty() ? "special" : "special-" + type;
      String through = null;
      if (fields.length > 3 && !fields[3].isBlank() && !fields[3].trim().equals("-1")) {
        through = fields[3].trim();
      }
      try {
        Adjacency adjacency = new Adjacency(
            Integer.toString(from), Integer.toString(to), kind, 0, through);
        if (seen.add(adjacency.a() + "|" + adjacency.b() + "|" + adjacency.kind())) {
          special.add(adjacency);
        }
      } catch (IllegalArgumentException e) {
        warnings.add("adjacency 行非法，跳过 line=" + (lineIndex + 1) + ": " + e.getMessage());
      }
    }
    return special;
  }

  private static Map<String, Railway> parseRailways(Path file, Set<Integer> used,
      String[] kinds, List<String> warnings) {
    if (!Files.isRegularFile(file)) {
      warnings.add("缺少 map/railways.txt，导入零条 railway");
      return Map.of();
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_RAILWAY_READ_FAILED", "读取 railways.txt 失败", e);
    }
    Map<String, Railway> railways = new LinkedHashMap<>();
    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
      String line = lines.get(lineIndex).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      List<Integer> tokens = ints(line);
      if (tokens.size() < 4) {
        warnings.add("railway 行少于 level+count+2 省，跳过 line=" + (lineIndex + 1));
        continue;
      }
      int level = tokens.get(0);
      int provinceCount = tokens.get(1);
      if (provinceCount < 2 || tokens.size() != provinceCount + 2) {
        warnings.add("railway 行 count 与省数不匹配，跳过 line=" + (lineIndex + 1));
        continue;
      }
      List<String> path = new ArrayList<>();
      boolean valid = level >= 1;
      for (int i = 2; i < tokens.size(); i++) {
        int provinceId = tokens.get(i);
        if (!used.contains(provinceId) || provinceId >= kinds.length
            || !kinds[provinceId].equals("land")) {
          valid = false;
          break;
        }
        path.add(Integer.toString(provinceId));
      }
      if (!valid) {
        warnings.add("railway 行引用无效/非陆省，跳过 line=" + (lineIndex + 1));
        continue;
      }
      String id = String.format("railway-%04d", lineIndex + 1);
      try {
        railways.put(id, new Railway(id, level, path));
      } catch (IllegalArgumentException e) {
        warnings.add("railway 行非法，跳过 line=" + (lineIndex + 1) + ": " + e.getMessage());
      }
    }
    return railways;
  }

  private static DefinitionTable parseDefinitions(Path file) {
    List<Definition> rows = new ArrayList<>();
    int maxId = 0;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      String line;
      boolean first = true;
      while ((line = reader.readLine()) != null) {
        if (first) {
          first = false;
          if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            line = line.substring(1);
          }
        }
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split(";", -1);
        if (fields.length < 8) {
          throw new Hoi4ImportException("HOI4_DEFINITION_INVALID",
              "definition.csv 行字段不足: " + line);
        }
        int id = parseInt(fields[0], "definition id", line);
        int r = parseInt(fields[1], "definition r", line);
        int g = parseInt(fields[2], "definition g", line);
        int b = parseInt(fields[3], "definition b", line);
        String type = fields[4].trim();
        String terrain = fields[6].trim();
        if (id < 0 || r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
          throw new Hoi4ImportException("HOI4_DEFINITION_INVALID", "definition 数值越界: " + line);
        }
        if (!Set.of("land", "sea", "lake").contains(type)) {
          throw new Hoi4ImportException("HOI4_DEFINITION_INVALID", "未知 province type: " + line);
        }
        rows.add(new Definition(id, (r << 16) | (g << 8) | b,
            type, terrain.isBlank() ? "unknown" : terrain));
        maxId = Math.max(maxId, id);
      }
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_DEFINITION_READ_FAILED",
          "读取 definition.csv 失败: " + file, e);
    }
    if (rows.isEmpty()) {
      throw new Hoi4ImportException("HOI4_DEFINITION_INVALID", "definition.csv 为空");
    }
    Definition[] byId = new Definition[maxId + 1];
    int[] colorToId = new int[1 << 24];
    Arrays.fill(colorToId, -1);
    for (Definition definition : rows) {
      if (byId[definition.id()] != null) {
        throw new Hoi4ImportException("HOI4_DEFINITION_INVALID",
            "重复 province id: " + definition.id());
      }
      if (colorToId[definition.rgb()] >= 0) {
        throw new Hoi4ImportException("HOI4_DEFINITION_INVALID",
            "重复 province 颜色: " + Integer.toHexString(definition.rgb()));
      }
      byId[definition.id()] = definition;
      colorToId[definition.rgb()] = definition.id();
    }
    return new DefinitionTable(byId, colorToId);
  }

  private static ProvinceKinds inferProvinceKinds(Path heightmapFile, Definition[] definitions,
      int[] pixelCounts, int width, int height, int[] provinceIds, Set<Integer> used,
      List<String> warnings) {
    String[] kinds = new String[definitions.length];
    for (int id : used) {
      Definition definition = definitions[id];
      if (definition == null) {
        throw new Hoi4ImportException("HOI4_DEFINITION_MISSING", "使用中的省 id 无 definition: " + id);
      }
      kinds[id] = definition.type();
    }
    if (!Files.isRegularFile(heightmapFile)) {
      warnings.add("缺少 map/heightmap.bmp，跳过海陆类型修复");
      return new ProvinceKinds(kinds, 0);
    }
    Bmp8Reader.Raster heightmap;
    try {
      heightmap = Bmp8Reader.read(heightmapFile);
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_HEIGHTMAP_READ_FAILED",
          "读取 heightmap.bmp 失败: " + heightmapFile, e);
    }
    if (heightmap.width() != width || heightmap.height() != height) {
      throw new Hoi4ImportException("HOI4_HEIGHTMAP_SIZE_MISMATCH",
          "heightmap 尺寸与 provinces.bmp 不一致: " + heightmap.width() + "x" + heightmap.height()
              + " vs " + width + "x" + height);
    }
    int[] waterPixels = new int[definitions.length];
    for (int i = 0; i < heightmap.values().length; i++) {
      if (heightmap.values()[i] <= WATER_HEIGHT_MAX) {
        waterPixels[provinceIds[i]]++;
      }
    }
    int inferred = 0;
    for (int id : used) {
      if (kinds[id].equals("land") && pixelCounts[id] > 0
          && waterPixels[id] * 2 >= pixelCounts[id]) {
        kinds[id] = "sea";
        inferred++;
      }
    }
    if (inferred > 0) {
      warnings.add("heightmap 推断 " + inferred + " 个 definition=land 省为 sea");
    }
    return new ProvinceKinds(kinds, inferred);
  }

  private static ParsedMapData parseStates(Path directory, Set<Integer> used, String[] kinds,
      List<String> warnings) {
    Map<Integer, Integer> provinceState = new HashMap<>();
    Map<Integer, String> stateNameKeys = new HashMap<>();
    int duplicateAssignments = 0;
    try (Stream<Path> paths = Files.list(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        String text = readText(path, "state");
        List<String> blocks = extractBlocks(text, "state");
        if (blocks.isEmpty()) {
          blocks = List.of(text);
        } else if (blocks.size() == 1
            && intsInProvinces(blocks.get(0)).isEmpty()
            && !intsInProvinces(text).isEmpty()) {
          // 少数社区文件把 provinces 写到了 state 的提前闭合括号之外。
          blocks = List.of(text);
        }
        for (String block : blocks) {
          Integer stateId = firstInt(ID_FIELD, block);
          if (stateId == null) {
            warnings.add("state 文件缺 id: " + path.getFileName());
            continue;
          }
          String nameKey = firstString(NAME_FIELD, block);
          if (nameKey != null) {
            stateNameKeys.putIfAbsent(stateId, nameKey);
          }
          for (int provinceId : intsInProvinces(block)) {
            if (!used.contains(provinceId) || provinceId >= kinds.length) {
              continue;
            }
            if (kinds[provinceId].equals("sea")) {
              continue;
            }
            Integer previous = provinceState.putIfAbsent(provinceId, stateId);
            if (previous != null && !previous.equals(stateId)) {
              duplicateAssignments++;
            }
          }
        }
      }
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_STATE_READ_FAILED",
          "遍历 history/states 失败: " + directory, e);
    }
    if (duplicateAssignments > 0) {
      throw new Hoi4ImportException("HOI4_STATE_CONFLICT",
          duplicateAssignments + " 个省被多个 state 声明");
    }
    return new ParsedMapData(provinceState, stateNameKeys);
  }

  private static ParsedRegionData parseRegions(Path directory, Set<Integer> used,
      List<String> warnings) {
    Map<Integer, String> provinceRegion = new HashMap<>();
    Map<Integer, String> regionNameKeys = new HashMap<>();
    int duplicateAssignments = 0;
    try (Stream<Path> paths = Files.list(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        String text = readText(path, "strategic region");
        List<String> blocks = extractBlocks(text, "strategic_region");
        if (blocks.isEmpty()) {
          warnings.add("strategic region 文件无 block: " + path.getFileName());
          continue;
        }
        for (String block : blocks) {
          Integer regionId = firstInt(ID_FIELD, block);
          if (regionId == null) {
            warnings.add("strategic region 缺 id: " + path.getFileName());
            continue;
          }
          String nameKey = firstString(NAME_FIELD, block);
          if (nameKey != null) {
            regionNameKeys.putIfAbsent(regionId, nameKey);
          }
          String regionIdText = Integer.toString(regionId);
          for (int provinceId : intsInProvinces(block)) {
            if (!used.contains(provinceId)) {
              continue;
            }
            String previous = provinceRegion.putIfAbsent(provinceId, regionIdText);
            if (previous != null && !previous.equals(regionIdText)) {
              duplicateAssignments++;
            }
          }
        }
      }
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_REGION_READ_FAILED",
          "遍历 map/strategicregions 失败: " + directory, e);
    }
    if (duplicateAssignments > 0) {
      throw new Hoi4ImportException("HOI4_REGION_CONFLICT",
          duplicateAssignments + " 个省被多个 strategic region 声明");
    }
    return new ParsedRegionData(provinceRegion, regionNameKeys);
  }

  private static Map<String, String> parseLocalisation(Path directory) {
    if (!Files.isDirectory(directory)) {
      return Map.of();
    }
    Map<String, String> values = new HashMap<>();
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".yml"))
          .sorted().toList()) {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String raw : lines) {
          String line = raw;
          if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            line = line.substring(1);
          }
          Matcher matcher = LOCALISATION.matcher(line);
          if (matcher.matches()) {
            String key = matcher.group(1);
            if (key.startsWith("STATE_") || key.startsWith("STRATEGICREGION_")
                || key.startsWith("VICTORY_POINTS_")) {
              values.putIfAbsent(key, matcher.group(2));
            }
          }
        }
      }
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_LOCALISATION_READ_FAILED",
          "读取 localisation 失败: " + directory, e);
    }
    return values;
  }

  private static List<String> extractBlocks(String text, String keyword) {
    List<String> blocks = new ArrayList<>();
    int search = 0;
    while (true) {
      int keywordIndex = text.indexOf(keyword, search);
      if (keywordIndex < 0) {
        break;
      }
      int brace = text.indexOf('{', keywordIndex + keyword.length());
      if (brace < 0) {
        break;
      }
      int depth = 0;
      boolean inString = false;
      boolean escaped = false;
      boolean comment = false;
      int end = -1;
      for (int i = brace; i < text.length(); i++) {
        char c = text.charAt(i);
        if (comment) {
          if (c == '\n' || c == '\r') {
            comment = false;
          }
          continue;
        }
        if (inString) {
          if (escaped) {
            escaped = false;
          } else if (c == '\\') {
            escaped = true;
          } else if (c == '"') {
            inString = false;
          }
          continue;
        }
        if (c == '#') {
          comment = true;
        } else if (c == '"') {
          inString = true;
        } else if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
          if (depth == 0) {
            end = i;
            break;
          }
        }
      }
      if (end < 0) {
        // Mappa Mundi 有少量截断 state 文件；保留已写出的字段/省列表，避免整州丢失。
        blocks.add(text.substring(brace + 1));
        break;
      }
      blocks.add(text.substring(brace + 1, end));
      search = end + 1;
    }
    return blocks;
  }

  private static List<Integer> intsInProvinces(String block) {
    Matcher matcher = PROVINCES_FIELD.matcher(block);
    if (!matcher.find()) {
      return List.of();
    }
    return ints(matcher.group(1));
  }

  private static List<Integer> ints(String text) {
    List<Integer> values = new ArrayList<>();
    Matcher matcher = INT_TOKEN.matcher(text);
    while (matcher.find()) {
      try {
        values.add(Integer.parseInt(matcher.group()));
      } catch (NumberFormatException e) {
        throw new Hoi4ImportException("HOI4_TEXT_NUMBER_OVERFLOW", matcher.group(), e);
      }
    }
    return values;
  }

  private static Integer firstInt(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException e) {
      throw new Hoi4ImportException("HOI4_TEXT_NUMBER_OVERFLOW", matcher.group(1), e);
    }
  }

  private static String firstString(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static int parseInt(String text, String field, String line) {
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      throw new Hoi4ImportException("HOI4_DEFINITION_INVALID",
          field + " 不是整数: " + line, e);
    }
  }

  private static String adjacencyKind(String a, String b) {
    if (a.equals("sea") || b.equals("sea")) {
      return "sea";
    }
    if (a.equals("lake") || b.equals("lake")) {
      return "lake";
    }
    return "land";
  }

  private static String readText(Path path, String kind) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new Hoi4ImportException("HOI4_TEXT_READ_FAILED",
          "读取 " + kind + " 文件失败: " + path, e);
    }
  }

  private static void requireFile(Path path) {
    if (!Files.isRegularFile(path)) {
      throw new Hoi4ImportException("HOI4_SOURCE_MISSING", "缺少文件: " + path);
    }
  }

  private static void requireDirectory(Path path) {
    if (!Files.isDirectory(path)) {
      throw new Hoi4ImportException("HOI4_SOURCE_MISSING", "缺少目录: " + path);
    }
  }

  private static String formatIssues(List<ValidationIssue> issues) {
    StringBuilder message = new StringBuilder("导入结果未通过静态地图校验: ")
        .append(issues.size()).append(" 个问题");
    int shown = Math.min(10, issues.size());
    for (int i = 0; i < shown; i++) {
      ValidationIssue issue = issues.get(i);
      message.append("\n - ").append(issue.code()).append(' ').append(issue.message());
    }
    if (issues.size() > shown) {
      message.append("\n - ...");
    }
    return message.toString();
  }

  public record ImportResult(StaticWorldState state, ImportStats stats, List<String> warnings) {
    public ImportResult {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(stats, "stats");
      warnings = List.copyOf(warnings);
    }
  }

  public record ImportStats(
      int width,
      int height,
      int definitionProvinces,
      int usedProvinces,
      int states,
      int regions,
      int adjacencies,
      int supplyHubs,
      int railways,
      int rings,
      int repairedPolygons,
      int inferredSeaProvinces,
      int warnings) {}

  private record ProvinceKinds(String[] kinds, int inferredSeaProvinces) {}

  private record Definition(int id, int rgb, String type, String terrain) {}

  private record DefinitionTable(Definition[] byId, int[] colorToId) {}

  private static final class ParsedMapData {
    private final Map<Integer, Integer> provinceState;
    private final Map<Integer, String> stateNameKeys;
    private boolean unassignedState;

    private ParsedMapData(Map<Integer, Integer> provinceState, Map<Integer, String> stateNameKeys) {
      this.provinceState = provinceState;
      this.stateNameKeys = stateNameKeys;
    }

    Map<Integer, Integer> provinceState() {
      return provinceState;
    }

    Map<Integer, String> stateNameKeys() {
      return stateNameKeys;
    }

    boolean unassignedState() {
      return unassignedState;
    }

    void setUnassignedState(boolean unassignedState) {
      this.unassignedState = unassignedState;
    }
  }

  private static final class ParsedRegionData {
    private final Map<Integer, String> provinceRegion;
    private final Map<Integer, String> regionNameKeys;
    private boolean unassignedRegion;

    private ParsedRegionData(Map<Integer, String> provinceRegion, Map<Integer, String> regionNameKeys) {
      this.provinceRegion = provinceRegion;
      this.regionNameKeys = regionNameKeys;
    }

    Map<Integer, String> provinceRegion() {
      return provinceRegion;
    }

    Map<Integer, String> regionNameKeys() {
      return regionNameKeys;
    }

    boolean unassignedRegion() {
      return unassignedRegion;
    }

    void setUnassignedRegion(boolean unassignedRegion) {
      this.unassignedRegion = unassignedRegion;
    }
  }
}
