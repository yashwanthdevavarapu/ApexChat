package com.apexchat.common;

import com.apexchat.config.ChatProperties;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window rate limiter keyed by username.
 *
 * <p>Limits apply per user rather than per session, so opening extra tabs does not raise a quota.
 */
@Component
public class RateLimiter {

  private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int maxMessages;
  private final long windowMillis;

  public RateLimiter(ChatProperties properties, Clock clock) {
    this.clock = clock;
    this.maxMessages = properties.rateLimit().maxMessages();
    this.windowMillis = properties.rateLimit().window().toMillis();
  }

  /**
   * Records an attempt for {@code key}.
   *
   * @throws RateLimitExceededException when the window is already full
   */
  public void acquire(String key) {
    long now = clock.millis();
    Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
        window.pollFirst();
      }
      if (window.size() >= maxMessages) {
        throw new RateLimitExceededException();
      }
      window.addLast(now);
    }
  }
}
