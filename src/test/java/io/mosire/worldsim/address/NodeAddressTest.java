package io.mosire.worldsim.address;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeAddressTest {
  @Test
  void parsesNodeAndEntityPath() {
    NodeAddress address = NodeAddress.parse("tree1-360/map/province/berlin");

    assertEquals(new NodeRef(new BranchId("tree1"), 360), address.node());
    assertEquals(List.of("map", "province", "berlin"), address.path());
    assertEquals("map", address.type().orElseThrow());
    assertEquals(List.of("province", "berlin"), address.rest());
    assertEquals("tree1-360/map/province/berlin", address.canonical());
  }

  @Test
  void branchMayContainHyphensAndTickIsTrailingNumber() {
    NodeAddress address = NodeAddress.parse("tree-alpha-42/unit/army/x");
    assertEquals(new BranchId("tree-alpha"), address.node().branch());
    assertEquals(42, address.node().tick());
  }

  @Test
  void nodeOnlyAddressIsAllowedButEntityPathCanBeRequired() {
    NodeAddress address = NodeAddress.parse("tree1-0");
    assertTrue(address.path().isEmpty());
    assertTrue(address.type().isEmpty());
    AddressException error = assertThrows(AddressException.class, address::requireEntityPath);
    assertEquals("ADDRESS_ENTITY_PATH_MISSING", error.code());
  }

  @Test
  void rejectsWhitespaceEmptySegmentsAndDotSegments() {
    assertEquals("ADDRESS_EMPTY", assertThrows(AddressException.class,
        () -> NodeAddress.parse("")).code());
    assertEquals("ADDRESS_EMPTY", assertThrows(AddressException.class,
        () -> NodeAddress.parse("   ")).code());
    assertEquals("ADDRESS_WHITESPACE", assertThrows(AddressException.class,
        () -> NodeAddress.parse(" tree1-0")).code());
    assertEquals("ADDRESS_WHITESPACE", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1 0/unit")).code());
    assertEquals("ADDRESS_WHITESPACE", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1-0/unit/a b")).code());
    assertEquals("ADDRESS_EMPTY_SEGMENT", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1-0/unit//x")).code());
    assertEquals("ADDRESS_SEGMENT_DOT", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1-0/unit/../x")).code());
    assertEquals("ADDRESS_SEGMENT_DOT", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1-0/unit/.")).code());
  }

  @Test
  void rejectsMalformedNodeCoordinates() {
    assertEquals("ADDRESS_NODE_FORMAT", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1/unit")).code());
    assertEquals("ADDRESS_NODE_FORMAT", assertThrows(AddressException.class,
        () -> NodeAddress.parse("-360/unit")).code());
    assertEquals("ADDRESS_TICK", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1-x/unit")).code());
    assertEquals("ADDRESS_TICK_OVERFLOW", assertThrows(AddressException.class,
        () -> NodeAddress.parse("tree1-99999999999999999999/unit")).code());
    assertEquals("ADDRESS_BRANCH", assertThrows(AddressException.class,
        () -> NodeAddress.parse("old:namespace-1/unit")).code());
  }

  @Test
  void directConstructionCannotSmuggleInvalidSegments() {
    assertThrows(IllegalArgumentException.class, () -> new BranchId("a/b"));
    assertThrows(IllegalArgumentException.class, () -> new BranchId("a b"));
    assertThrows(IllegalArgumentException.class, () -> new BranchId("a:b"));

    NodeRef ref = new NodeRef(new BranchId("tree1"), 0);
    assertEquals("ADDRESS_SEGMENT_SLASH", assertThrows(AddressException.class,
        () -> new NodeAddress(ref, List.of("unit\\x"))).code());
    assertEquals("ADDRESS_SEGMENT_BLANK", assertThrows(AddressException.class,
        () -> new NodeAddress(ref, List.of(" "))).code());
    assertEquals("ADDRESS_SEGMENT_WHITESPACE", assertThrows(AddressException.class,
        () -> new NodeAddress(ref, List.of("unit x"))).code());
  }

  @Test
  void addressLengthLimitIsEnforced() {
    String longAddress = "tree1-0/" + "x".repeat(NodeAddress.MAX_LENGTH);
    assertEquals("ADDRESS_TOO_LONG", assertThrows(AddressException.class,
        () -> NodeAddress.parse(longAddress)).code());
  }

  @Test
  void looksLikeNodeAddressDoesNotThrow() {
    assertTrue(NodeAddress.looksLikeNodeAddress("tree1-360/unit/army/x"));
    assertTrue(NodeAddress.looksLikeNodeAddress("tree1-0"));
    assertFalse(NodeAddress.looksLikeNodeAddress("unit:u-1"));
    assertFalse(NodeAddress.looksLikeNodeAddress("tree1-"));
    assertFalse(NodeAddress.looksLikeNodeAddress("tree1-x"));
    assertFalse(NodeAddress.looksLikeNodeAddress("tree1-0/unit//x"));
    assertFalse(NodeAddress.looksLikeNodeAddress(null));
  }

  @Test
  void canonicalRoundTrips() {
    for (String text : List.of(
        "tree1-0",
        "tree1-360/map/province/berlin",
        "tree1-360/region/小世界",
        "tree-alpha-42/unit/gov/gov-central",
        "tree2-600/custom/weather/front-1")) {
      assertEquals(text, NodeAddress.parse(text).canonical());
      assertDoesNotThrow(() -> NodeAddress.parse(text));
    }
  }
}
