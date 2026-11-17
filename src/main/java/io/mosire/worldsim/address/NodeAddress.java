package io.mosire.worldsim.address;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 严格节点地址：{@code branch-tick[/<type>[/<id>...]]}。
 *
 * <p>语义沿用 SimulatorMosire 的 NodeAddress：
 *
 * <ul>
 *   <li>首段必须是节点坐标；分支名允许含 '-'，以最后一个 '-' 划分 branch/tick；
 *   <li>tick 必须是非负十进制整数；
 *   <li>地址不得含空白、空段、'.'/'..' 路径段；
 *   <li>路径段不得含 '/' 或 '\\'；
 *   <li>首段之外的路径只做语法校验，不解释实体语义。
 * </ul>
 *
 * <p>本类只负责定位语法；实体是否存在、是否可见由 resolver/权限层回答。
 */
public record NodeAddress(NodeRef node, List<String> path) {
  public static final int MAX_LENGTH = 512;

  public NodeAddress {
    Objects.requireNonNull(node, "node");
    path = List.copyOf(Objects.requireNonNull(path, "path"));
    for (String segment : path) {
      if (segment == null || segment.isBlank()) {
        throw new AddressException("ADDRESS_SEGMENT_BLANK", "节点地址的路径段不得为空白: " + path);
      }
      if (segment.chars().anyMatch(NodeAddress::isAddressWhitespace)) {
        throw new AddressException("ADDRESS_SEGMENT_WHITESPACE", "节点地址的路径段不得含空白: " + segment);
      }
      if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
        throw new AddressException("ADDRESS_SEGMENT_SLASH", "节点地址的路径段不得含 '/' 或 '\\\\': " + segment);
      }
      if (segment.equals(".") || segment.equals("..")) {
        throw new AddressException("ADDRESS_SEGMENT_DOT", "节点地址的路径段不得为 '.' / '..': " + segment);
      }
    }
  }

  public static NodeAddress parse(String text) {
    if (text == null || text.isBlank()) {
      throw new AddressException("ADDRESS_EMPTY", "节点地址不得为空");
    }
    if (!text.equals(text.strip())) {
      throw new AddressException("ADDRESS_WHITESPACE", "节点地址首尾不得含空白: `" + text + "`");
    }
    String value = text;
    if (value.chars().anyMatch(NodeAddress::isAddressWhitespace)) {
      throw new AddressException("ADDRESS_WHITESPACE", "节点地址不得含空白: " + value);
    }
    if (value.length() > MAX_LENGTH) {
      throw new AddressException("ADDRESS_TOO_LONG", "节点地址过长（上限 " + MAX_LENGTH + "）: " + value.length());
    }
    String[] raw = value.split("/", -1);
    List<String> segments = new ArrayList<>(raw.length);
    for (String segment : raw) {
      if (segment.isEmpty()) {
        throw new AddressException("ADDRESS_EMPTY_SEGMENT", "节点地址含空段: " + value);
      }
      segments.add(segment);
    }
    NodeRef node = parseNode(segments.get(0), value);
    List<String> path = segments.size() == 1 ? List.of() : segments.subList(1, segments.size());
    return new NodeAddress(node, path);
  }

  public static boolean looksLikeNodeAddress(String text) {
    try {
      parse(text);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  static boolean isAddressWhitespace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  private static NodeRef parseNode(String segment, String whole) {
    int dash = segment.lastIndexOf('-');
    if (dash <= 0 || dash == segment.length() - 1) {
      throw new AddressException(
          "ADDRESS_NODE_FORMAT",
          "节点地址首段必须是 <branch>-<tick>（如 tree1-360）: `" + segment + "`（地址：`" + whole + "`）");
    }
    String branch = segment.substring(0, dash);
    String tickText = segment.substring(dash + 1);
    if (branch.isBlank() || branch.indexOf(':') >= 0) {
      throw new AddressException(
          "ADDRESS_BRANCH",
          "节点地址的分支名不得为空白或含 ':'（旧 namespace 地址不是节点地址）: `" + whole + "`");
    }
    if (branch.chars().anyMatch(NodeAddress::isAddressWhitespace)
        || branch.indexOf('/') >= 0 || branch.indexOf('\\') >= 0) {
      throw new AddressException("ADDRESS_BRANCH", "节点地址的分支名含非法字符: `" + branch + "`");
    }
    if (!tickText.chars().allMatch(Character::isDigit)) {
      throw new AddressException(
          "ADDRESS_TICK",
          "节点地址的 tick 必须是非负十进制整数: `" + tickText + "`（地址：`" + whole + "`）");
    }
    long tick;
    try {
      tick = Long.parseLong(tickText);
    } catch (NumberFormatException e) {
      throw new AddressException("ADDRESS_TICK_OVERFLOW", "节点地址的 tick 溢出: `" + tickText + "`");
    }
    return new NodeRef(new BranchId(branch), tick);
  }

  public String canonical() {
    if (path.isEmpty()) {
      return node.label();
    }
    return node.label() + "/" + String.join("/", path);
  }

  public Optional<String> type() {
    return path.isEmpty() ? Optional.empty() : Optional.of(path.get(0));
  }

  public List<String> rest() {
    return path.isEmpty() ? List.of() : List.copyOf(path.subList(1, path.size()));
  }

  public NodeAddress requireEntityPath() {
    if (path.isEmpty()) {
      throw new AddressException("ADDRESS_ENTITY_PATH_MISSING", "该地址只指向节点，缺少实体路径: " + canonical());
    }
    return this;
  }

  @Override
  public String toString() {
    return canonical();
  }
}
