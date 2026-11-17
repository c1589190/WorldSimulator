package io.mosire.worldsim.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 统一 JSON 编解码：稳定排序 + 严格反序列化 + SHA-256 内容哈希。 */
public final class JsonCodec {
  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      .build();

  private JsonCodec() {}

  public static String canonicalJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("无法序列化为规范 JSON: " + value.getClass().getName(), e);
    }
  }

  public static String prettyJson(Object value) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("无法序列化为 JSON: " + value.getClass().getName(), e);
    }
  }

  public static <T> T fromJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("无法从 JSON 读取 " + type.getName(), e);
    }
  }

  public static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK 缺少 SHA-256", e);
    }
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }
}
