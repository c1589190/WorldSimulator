package io.mosire.worldsim.timeline;

/** 时间线操作失败的稳定错误码。 */
public final class TimelineException extends RuntimeException {
  private final String code;

  public TimelineException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
