package com.apexchat.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apexchat.support.MutableClock;
import com.apexchat.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private MutableClock clock;
  private RateLimiter limiter;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    limiter = new RateLimiter(TestProperties.withRateLimit(3, Duration.ofSeconds(10)), clock);
  }

  @Test
  void allowsUpToTheLimitWithinOneWindow() {
    limiter.acquire("alice");
    limiter.acquire("alice");
    limiter.acquire("alice");

    assertThatThrownBy(() -> limiter.acquire("alice"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessageContaining("too quickly");
  }

  @Test
  void slidesTheWindowAsTimePasses() {
    limiter.acquire("alice");
    clock.advance(Duration.ofSeconds(4));
    limiter.acquire("alice");
    limiter.acquire("alice");

    clock.advance(Duration.ofSeconds(6)); // the first attempt is now exactly 10s old
    assertThatCode(() -> limiter.acquire("alice")).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.acquire("alice"))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void tracksEachUserIndependently() {
    for (int i = 0; i < 3; i++) {
      limiter.acquire("alice");
    }
    assertThatCode(() -> limiter.acquire("bob")).doesNotThrowAnyException();
  }

  @Test
  void rejectedAttemptsDoNotConsumeQuota() {
    for (int i = 0; i < 3; i++) {
      limiter.acquire("alice");
    }
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> limiter.acquire("alice"))
          .isInstanceOf(RateLimitExceededException.class);
    }
    clock.advance(Duration.ofSeconds(10));
    assertThatCode(() -> limiter.acquire("alice")).doesNotThrowAnyException();
  }
}
