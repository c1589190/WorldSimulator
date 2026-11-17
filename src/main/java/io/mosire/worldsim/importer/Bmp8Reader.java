package io.mosire.worldsim.importer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只读 8-bit 未压缩 BMP，返回 palette index（HOI4 heightmap 的 index 即高度）。 */
final class Bmp8Reader {
  private Bmp8Reader() {}

  static Raster read(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    if (bytes.length < 54 || bytes[0] != 'B' || bytes[1] != 'M') {
      throw new Hoi4ImportException("HOI4_HEIGHTMAP_INVALID", "不是 BMP 文件: " + path);
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int pixelOffset = buffer.getInt(10);
    int headerSize = buffer.getInt(14);
    int width = buffer.getInt(18);
    int rawHeight = buffer.getInt(22);
    int planes = Short.toUnsignedInt(buffer.getShort(26));
    int bitsPerPixel = Short.toUnsignedInt(buffer.getShort(28));
    int compression = buffer.getInt(30);
    if (headerSize < 40 || width <= 0 || rawHeight == 0 || planes != 1
        || bitsPerPixel != 8 || compression != 0) {
      throw new Hoi4ImportException("HOI4_HEIGHTMAP_UNSUPPORTED",
          "只支持 8-bit 未压缩 heightmap BMP: `" + path + "` (bpp=" + bitsPerPixel
              + ", compression=" + compression + ", size=" + width + "x" + rawHeight + ")");
    }
    boolean topDown = rawHeight < 0;
    int height = Math.abs(rawHeight);
    int rowStride = (width + 3) / 4 * 4;
    long required = (long) pixelOffset + (long) rowStride * height;
    if (required > bytes.length) {
      throw new Hoi4ImportException("HOI4_HEIGHTMAP_INVALID",
          "heightmap 数据不完整: need=" + required + " actual=" + bytes.length);
    }
    int[] values = new int[Math.multiplyExact(width, height)];
    for (int y = 0; y < height; y++) {
      int sourceRow = topDown ? y : height - 1 - y;
      int rowStart = pixelOffset + sourceRow * rowStride;
      int out = y * width;
      for (int x = 0; x < width; x++) {
        values[out + x] = Byte.toUnsignedInt(bytes[rowStart + x]);
      }
    }
    return new Raster(width, height, values);
  }

  record Raster(int width, int height, int[] values) {}
}
