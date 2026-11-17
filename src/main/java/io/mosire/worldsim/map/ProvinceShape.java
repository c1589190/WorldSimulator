package io.mosire.worldsim.map;

import java.util.List;
import java.util.Objects;

/**
 * 省/地块的原生几何：一个或多个 polygon part。
 *
 * <p>支持多 part 是为了岛屿、飞地等真实省形；首版不把它降级成栅格。
 */
public record ProvinceShape(List<PolygonPart> parts) {
  public ProvinceShape {
    parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("ProvinceShape 至少需要一个 PolygonPart");
    }
    for (PolygonPart part : parts) {
      Objects.requireNonNull(part, "part");
    }
  }

  public static ProvinceShape of(PolygonPart part) {
    return new ProvinceShape(List.of(part));
  }

  public static ProvinceShape of(Ring outer) {
    return of(PolygonPart.of(outer));
  }
}
