package io.mosire.worldsim.importer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 供 importer/CLI 测试共享的 4×2 HOI4 地图 fixture。 */
public final class Hoi4FixtureFiles {
  private Hoi4FixtureFiles() {}

  public static Path write(Path root) throws IOException {
    Path mapDir = Files.createDirectories(root.resolve("map"));
    Path statesDir = Files.createDirectories(root.resolve("history/states"));
    Path regionsDir = Files.createDirectories(root.resolve("map/strategicregions"));
    Path locDir = Files.createDirectories(root.resolve("localisation"));

    writeBmp(mapDir.resolve("provinces.bmp"));
    writeHeightmapBmp(mapDir.resolve("heightmap.bmp"), false);
    Files.writeString(mapDir.resolve("definition.csv"),
        "1;255;0;0;land;false;plains;1\n"
            + "2;0;255;0;land;false;forest;1\n",
        StandardCharsets.ISO_8859_1);
    Files.writeString(mapDir.resolve("supply_nodes.txt"), "1 1", StandardCharsets.UTF_8);
    Files.writeString(mapDir.resolve("railways.txt"), "1 2 1 2", StandardCharsets.UTF_8);
    Files.writeString(mapDir.resolve("adjacencies.csv"),
        "From;To;Type;Through;start_x;start_y;stop_x;stop_y;adjacency_rule_name;Comment\n"
            + "1;2;sea;1;-1;-1;-1;-1;;Test crossing\n",
        StandardCharsets.UTF_8);

    Files.writeString(statesDir.resolve("1-Alpha.txt"), """
        state = {
          id = 1
          name = "STATE_1"
          provinces = { 1 }
        }
        """, StandardCharsets.UTF_8);
    Files.writeString(statesDir.resolve("2-Beta.txt"), """
        state = {
          id = 2
          name = "STATE_2"
          provinces = { 2 }
        }
        """, StandardCharsets.UTF_8);
    Files.writeString(regionsDir.resolve("10-Core.txt"), """
        strategic_region = {
          id = 10
          name = "STRATEGICREGION_10"
          provinces = { 1 2 }
        }
        """, StandardCharsets.UTF_8);
    Files.writeString(locDir.resolve("test_l_english.yml"), """
        l_english:
         STATE_1:0 "Alpha"
         STATE_2:0 "Beta"
         STRATEGICREGION_10:0 "Core"
         VICTORY_POINTS_1:0 "Alpha Province"
         VICTORY_POINTS_2:0 "Beta Province"
        """, StandardCharsets.UTF_8);
    return root;
  }

  /** 模拟部分社区地图的畸形 state：provinces 写在 state 提前闭合括号之外。 */
  public static Path writeWithProvincesOutsideStateBlock(Path root) throws IOException {
    write(root);
    Files.writeString(root.resolve("history/states/2-Beta.txt"), """
        state = {
          id = 2
          name = "STATE_2"
          history = {
            owner = FRA
          }
        }
        provinces { 2 }
        """, StandardCharsets.UTF_8);
    return root;
  }

  /** 与 write 相同的州/区域，但右半省在 heightmap 中为水面。 */
  public static Path writeWithWaterProvince(Path root) throws IOException {
    write(root);
    writeHeightmapBmp(root.resolve("map/heightmap.bmp"), true);
    return root;
  }

  private static void writeBmp(Path path) throws IOException {
    int width = 4;
    int height = 2;
    int rowStride = ((width * 3 + 3) / 4) * 4;
    int imageSize = rowStride * height;
    ByteBuffer bytes = ByteBuffer.allocate(54 + imageSize).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put((byte) 'B').put((byte) 'M');
    bytes.putInt(54 + imageSize);
    bytes.putShort((short) 0).putShort((short) 0);
    bytes.putInt(54);
    bytes.putInt(40);
    bytes.putInt(width);
    bytes.putInt(height);
    bytes.putShort((short) 1);
    bytes.putShort((short) 24);
    bytes.putInt(0);
    bytes.putInt(imageSize);
    bytes.putInt(0).putInt(0).putInt(0).putInt(0);
    for (int sourceRow = 0; sourceRow < height; sourceRow++) {
      int rowStart = 54 + sourceRow * rowStride;
      for (int x = 0; x < width; x++) {
        int id = x < 2 ? 1 : 2;
        int offset = rowStart + x * 3;
        if (id == 1) {
          bytes.put(offset, (byte) 0);
          bytes.put(offset + 1, (byte) 0);
          bytes.put(offset + 2, (byte) 255);
        } else {
          bytes.put(offset, (byte) 0);
          bytes.put(offset + 1, (byte) 255);
          bytes.put(offset + 2, (byte) 0);
        }
      }
    }
    Files.write(path, bytes.array());
  }

  private static void writeHeightmapBmp(Path path, boolean waterRight) throws IOException {
    int width = 4;
    int height = 2;
    int rowStride = (width + 3) / 4 * 4;
    int imageSize = rowStride * height;
    int pixelOffset = 54 + 256 * 4;
    ByteBuffer bytes = ByteBuffer.allocate(pixelOffset + imageSize).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put((byte) 'B').put((byte) 'M');
    bytes.putInt(pixelOffset + imageSize);
    bytes.putShort((short) 0).putShort((short) 0);
    bytes.putInt(pixelOffset);
    bytes.putInt(40);
    bytes.putInt(width);
    bytes.putInt(height);
    bytes.putShort((short) 1);
    bytes.putShort((short) 8);
    bytes.putInt(0);
    bytes.putInt(imageSize);
    bytes.putInt(0).putInt(0).putInt(0).putInt(0);
    for (int i = 0; i < 256; i++) {
      bytes.put((byte) i).put((byte) i).put((byte) i).put((byte) 0);
    }
    for (int sourceRow = 0; sourceRow < height; sourceRow++) {
      int rowStart = pixelOffset + sourceRow * rowStride;
      for (int x = 0; x < width; x++) {
        int value = waterRight && x >= 2 ? 95 : 100;
        bytes.put(rowStart + x, (byte) value);
      }
    }
    Files.write(path, bytes.array());
  }
}
