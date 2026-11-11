package com.apexchat.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apexchat.support.MutableClock;
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
    limiter = new RateLimiter(3, Duration.ofSeconds(10), clock);
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

  @Test
  void tryAcquireReportsInsteadOfThrowing() {
    for (int i = 0; i < 3; i++) {
      org.assertj.core.api.Assertions.assertThat(limiter.tryAcquire("typer")).isTrue();
    }
    org.assertj.core.api.Assertions.assertThat(limiter.tryAcquire("typer")).isFalse();
  }

  @Test
  void idleKeysAreSweptSoMemoryDoesNotGrowForever() {
    for (int i = 0; i < 1000; i++) {
      limiter.acquire("one-off-user-" + i);
    }
    clock.advance(Duration.ofSeconds(11));
    for (int i = 0; i < 100; i++) {
      limiter.acquire("active"); // keeps calling until a sweep triggers
      clock.advance(Duration.ofSeconds(4));
    }
    org.assertj.core.api.Assertions.assertThat(limiter.trackedKeys()).isLessThan(10);
  }
}
