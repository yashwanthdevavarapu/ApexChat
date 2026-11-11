package com.apexchat.support;

import com.apexchat.config.ChatProperties;
import java.time.Duration;
import java.util.List;

/** Builds {@link ChatProperties} for unit tests without starting Spring. */
public final class TestProperties {

  private TestProperties() {}

  public static ChatProperties defaults() {
    return withRateLimit(20, Duration.ofSeconds(10));
  }

  public static ChatProperties withRateLimit(int maxMessages, Duration window) {
    return new ChatProperties(
        2000, 50, 100, List.of(), new ChatProperties.RateLimit(maxMessages, window));
  }
}
