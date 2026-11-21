package io.mosire.worldsim.state;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Provenance of a claim, independent of the simulation tick. */
public record Evidence(String source, String status, Double confidence, String date) {
  private static final Set<String> STATUSES = Set.of("known", "estimated", "inferred", "unknown", "legacy-ambiguous");

  public Evidence {
    if (status == null || !STATUSES.contains(status)) throw new IllegalArgumentException("无效证据状态: " + status);
    if ((source == null || source.isBlank()) && !status.equals("unknown")) {
      throw new IllegalArgumentException("证据来源不能为空");
    }
    if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
      throw new IllegalArgumentException("置信度须在 0 到 1 之间");
    }
    if (date != null) {
      try {
        LocalDate.parse(date);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("资料日期须采用 ISO 日期格式: " + date, e);
      }
    }
  }

  public static Evidence requireRecorded(Evidence value) {
    if (value == null || value.status.equals("unknown") || value.status.equals("legacy-ambiguous")) {
      throw new IllegalArgumentException("需要可用于结算的数量或裁决证据");
    }
    return value;
  }

  public static Evidence afterAdjustment(Evidence previous, Evidence cause) {
    requireRecorded(previous);
    requireRecorded(cause);
    String status = previous.status.equals("known") && cause.status.equals("known") ? "known" : "estimated";
    Double confidence = previous.confidence == null || cause.confidence == null
        ? null : Math.min(previous.confidence, cause.confidence);
    return new Evidence(cause.source, status, confidence, cause.date);
  }
}
