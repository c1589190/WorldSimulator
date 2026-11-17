package io.mosire.worldsim.timeline;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;

import java.nio.file.Path;

/**
 * 测试专用独立 JVM 探针：尝试保存一个节点，成功输出 OK，失败输出 ERR code。
 *
 * <p>参数：db ref parentOrDash stateHash
 */
public final class StoreProbe {
  private StoreProbe() {}

  public static void main(String[] args) {
    if (args.length != 4) {
      System.err.println("usage: StoreProbe <db> <ref> <parent|-> <stateHash>");
      System.exit(2);
    }
    try (SqliteTimelineStore store = new SqliteTimelineStore(Path.of(args[0]))) {
      NodeRef parent = args[2].equals("-") ? null : parse(args[2]);
      store.saveNode(new TimelineNode(
          parse(args[1]),
          parent,
          "{\"changes\":[]}",
          args[3],
          System.currentTimeMillis(),
          "probe:" + ProcessHandle.current().pid()), null);
      System.out.println("OK");
    } catch (TimelineException e) {
      System.out.println("ERR " + e.code());
      System.exit(1);
    } catch (Throwable e) {
      System.out.println("ERR " + e.getClass().getSimpleName() + " " + e.getMessage());
      System.exit(1);
    }
  }

  private static NodeRef parse(String text) {
    int dash = text.lastIndexOf('-');
    if (dash <= 0 || dash == text.length() - 1) {
      throw new IllegalArgumentException("bad node ref: " + text);
    }
    return new NodeRef(new BranchId(text.substring(0, dash)),
        Long.parseLong(text.substring(dash + 1)));
  }
}
