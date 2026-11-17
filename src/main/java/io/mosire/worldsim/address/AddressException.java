package io.mosire.worldsim.address;

/** 地址语法/解析失败。code 是稳定错误码，供 MCP 工具与测试判别。 */
public final class AddressException extends IllegalArgumentException {
  private final String code;

  public AddressException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
