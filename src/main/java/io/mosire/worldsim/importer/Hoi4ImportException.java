package io.mosire.worldsim.importer;

/** HOI4 社区地图导入失败的稳定错误码。 */
public final class Hoi4ImportException extends RuntimeException {
  private final String code;

  public Hoi4ImportException(String code, String message) {
    super(message);
    this.code = code;
  }

  public Hoi4ImportException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
