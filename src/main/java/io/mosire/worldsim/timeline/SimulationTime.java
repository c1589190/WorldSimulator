package io.mosire.worldsim.timeline;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The scenario calendar has second precision and uses a single local clock. */
public final class SimulationTime {
  private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);

  private SimulationTime() {}

  public static String normalize(String time) {
    try {
      if (time == null) throw new IllegalArgumentException("需要剧本时间");
      return format(LocalDateTime.parse(time));
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("剧本时间须为 ISO 日期时间", e);
    }
  }

  public static String format(LocalDateTime time) {
    if (time.getYear() < 1 || time.getYear() > 9999 || time.getNano() != 0) {
      throw new IllegalArgumentException("剧本时间须在 0001 至 9999 年之间，精度到秒");
    }
    return FORMAT.format(time);
  }
}
