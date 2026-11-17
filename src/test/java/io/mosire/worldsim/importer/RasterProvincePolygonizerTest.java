package io.mosire.worldsim.importer;

import io.mosire.worldsim.map.JtsGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterProvincePolygonizerTest {
  @Test
  void extractsTwoAdjacentRectanglesAndBorderLength() {
    int[] ids = {
        1, 1, 2, 2,
        1, 1, 2, 2
    };
    var result = RasterProvincePolygonizer.polygonize(4, 2, ids, Set.of(1, 2));

    assertEquals(2, result.shapes().size());
    assertEquals(1, result.shapes().get(1).parts().size());
    assertEquals(1, result.shapes().get(2).parts().size());
    assertTrue(JtsGeometry.toGeometry(result.shapes().get(1)).isValid());
    assertTrue(JtsGeometry.toGeometry(result.shapes().get(2)).isValid());

    Map<Long, Integer> borders = result.borderLengths();
    assertEquals(1, borders.size());
    long key = borders.keySet().iterator().next();
    assertEquals(1, RasterProvincePolygonizer.borderA(key));
    assertEquals(2, RasterProvincePolygonizer.borderB(key));
    assertEquals(2, borders.values().iterator().next());
  }

  @Test
  void createsAHoleForEnclosedProvince() {
    int[] ids = {
        1, 1, 1,
        1, 2, 1,
        1, 1, 1
    };
    var result = RasterProvincePolygonizer.polygonize(3, 3, ids, Set.of(1, 2));

    var outer = result.shapes().get(1);
    assertEquals(1, outer.parts().size());
    assertEquals(1, outer.parts().get(0).holes().size());
    assertTrue(JtsGeometry.toGeometry(outer).isValid());

    var inner = result.shapes().get(2);
    assertEquals(1, inner.parts().size());
    assertEquals(0, inner.parts().get(0).holes().size());
    assertTrue(JtsGeometry.toGeometry(inner).isValid());
  }

  @Test
  void createsMultiPartProvinceForIslands() {
    int[] ids = {
        2, 2, 2, 2, 2,
        2, 1, 2, 1, 2,
        2, 2, 2, 2, 2,
        2, 1, 2, 1, 2,
        2, 2, 2, 2, 2
    };
    var result = RasterProvincePolygonizer.polygonize(5, 5, ids, Set.of(1, 2));

    assertEquals(4, result.shapes().get(1).parts().size());
    for (var part : result.shapes().get(1).parts()) {
      assertEquals(0, part.holes().size());
    }
    assertTrue(JtsGeometry.toGeometry(result.shapes().get(1)).isValid());

    assertEquals(1, result.shapes().get(2).parts().size());
    assertEquals(4, result.shapes().get(2).parts().get(0).holes().size());
    assertTrue(JtsGeometry.toGeometry(result.shapes().get(2)).isValid());
  }
}
