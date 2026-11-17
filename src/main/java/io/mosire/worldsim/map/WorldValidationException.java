package io.mosire.worldsim.map;

import java.util.List;

public final class WorldValidationException extends RuntimeException {
  private final List<ValidationIssue> issues;

  public WorldValidationException(List<ValidationIssue> issues) {
    super("地图校验失败: " + issues.size() + " 个问题；首个: "
        + (issues.isEmpty() ? "<none>" : issues.get(0).code() + " " + issues.get(0).message()));
    this.issues = List.copyOf(issues);
  }

  public List<ValidationIssue> issues() {
    return issues;
  }
}
