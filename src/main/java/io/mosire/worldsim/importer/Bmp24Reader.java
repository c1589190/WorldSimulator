package io.mosire.worldsim.importer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只读 24-bit 未压缩 BMP；返回 top-down RGB 像素，避免 ImageIO 的调色板/方向差异。 */
final class Bmp24Reader {
  private Bmp24Reader() {}

  static Raster read(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    if (bytes.length < 54 || bytes[0] != 'B' || bytes[1] != 'M') {
      throw new Hoi4ImportException("HOI4_BMP_INVALID", "不是 BMP 文件: " + path);
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
        || bitsPerPixel != 24 || compression != 0) {
      throw new Hoi4ImportException("HOI4_BMP_UNSUPPORTED",
          "只支持 24-bit 未压缩 BMP: `" + path + "` (bpp=" + bitsPerPixel
              + ", compression=" + compression + ", size=" + width + "x" + rawHeight + ")");
    }
    boolean topDown = rawHeight < 0;
    int height = Math.abs(rawHeight);
    long rowStrideLong = ((long) width * 3L + 3L) / 4L * 4L;
    if (rowStrideLong > Integer.MAX_VALUE) {
      throw new Hoi4ImportException("HOI4_BMP_UNSUPPORTED", "BMP 行宽溢出: " + rowStrideLong);
    }
    int rowStride = (int) rowStrideLong;
    long required = (long) pixelOffset + (long) rowStride * height;
    if (required > bytes.length) {
      throw new Hoi4ImportException("HOI4_BMP_INVALID",
          "BMP 数据不完整: need=" + required + " actual=" + bytes.length);
    }
    int[] rgb = new int[Math.multiplyExact(width, height)];
    for (int y = 0; y < height; y++) {
      int sourceRow = topDown ? y : height - 1 - y;
      int rowStart = pixelOffset + sourceRow * rowStride;
      int out = y * width;
      for (int x = 0; x < width; x++) {
        int offset = rowStart + x * 3;
        int b = Byte.toUnsignedInt(bytes[offset]);
        int g = Byte.toUnsignedInt(bytes[offset + 1]);
        int r = Byte.toUnsignedInt(bytes[offset + 2]);
        rgb[out + x] = (r << 16) | (g << 8) | b;
      }
    }
    return new Raster(width, height, rgb);
  }

  record Raster(int width, int height, int[] rgb) {}
}
