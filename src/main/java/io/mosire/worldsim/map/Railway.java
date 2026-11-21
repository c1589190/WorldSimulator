package io.mosire.worldsim.map;

import io.mosire.worldsim.state.Evidence;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Rail alignment and optional operating evidence at a world node. */
public record Railway(String id, int level, List<String> provincePath, Operation operation) {
  public record Operation(String status, Double tonnesPerDay, Evidence evidence) {
    public Operation {
      if (status == null || !Set.of("open", "closed", "unknown").contains(status)) {
        throw new IllegalArgumentException("未知铁路运营状态: " + status);
      }
      if (tonnesPerDay != null && (!Double.isFinite(tonnesPerDay) || tonnesPerDay < 0
          || status.equals("closed") && tonnesPerDay != 0)) {
        throw new IllegalArgumentException("无效的铁路日运能");
      }
      Objects.requireNonNull(evidence, "铁路运营来源");
      if ((!status.equals("unknown") || tonnesPerDay != null)
          && (evidence.source() == null || evidence.status().equals("unknown") || evidence.status().equals("legacy-ambiguous"))) {
        throw new IllegalArgumentException("铁路运营数据需要有来源的证据");
      }
    }
  }

  public Railway(String id, int level, List<String> provincePath) {
    this(id, level, provincePath, null);
  }

  public boolean closed() { return operation != null && operation.status().equals("closed"); }

  public Railway {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Railway.id 不得为空白");
    }
    if (level < 1) {
      throw new IllegalArgumentException("Railway.level 必须 >= 1: " + level);
    }
    Objects.requireNonNull(provincePath, "provincePath");
    if (provincePath.size() < 2) {
      throw new IllegalArgumentException("Railway.provincePath 至少需要 2 个省: " + id);
    }
    for (String provinceId : provincePath) {
      if (provinceId == null || provinceId.isBlank()) {
        throw new IllegalArgumentException("Railway.provincePath 不得含空白: " + id);
      }
    }
    for (int i = 1; i < provincePath.size(); i++) {
      if (provincePath.get(i - 1).equals(provincePath.get(i))) {
        throw new IllegalArgumentException("Railway.provincePath 不得连续重复同一省: " + id);
      }
    }
    provincePath = List.copyOf(provincePath);
  }
}
