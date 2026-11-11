package com.apexchat.config;

import com.apexchat.common.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Separate budgets for messages (configurable) and typing hints (generous but bounded). */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

  /** Typing hints are sent once per burst of keystrokes, so 30 per 10 s is far above normal use. */
  static final int TYPING_EVENTS_PER_WINDOW = 30;

  static final Duration TYPING_WINDOW = Duration.ofSeconds(10);

  @Bean
  RateLimiter messageRateLimiter(ChatProperties properties, Clock clock) {
    return new RateLimiter(
        properties.rateLimit().maxMessages(), properties.rateLimit().window(), clock);
  }

  @Bean
  RateLimiter typingRateLimiter(Clock clock) {
    return new RateLimiter(TYPING_EVENTS_PER_WINDOW, TYPING_WINDOW, clock);
  }
}
