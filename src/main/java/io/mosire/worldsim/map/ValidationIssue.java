package io.mosire.worldsim.map;

public record ValidationIssue(ValidationSeverity severity, String code, String message) {
  public ValidationIssue {
    if (severity == null) throw new IllegalArgumentException("severity 不得为 null");
    if (code == null || code.isBlank()) throw new IllegalArgumentException("code 不得为空白");
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不得为空白");
  }
}
