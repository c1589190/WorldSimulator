package io.mosire.worldsim.map;

/** 平面坐标。地图使用逻辑坐标，不绑定经纬度或像素。 */
public record Vec2(double x, double y) {
  public Vec2 {
    if (!Double.isFinite(x) || !Double.isFinite(y)) {
      throw new IllegalArgumentException("Vec2 坐标必须是有限数: (" + x + ", " + y + ")");
    }
  }
}
