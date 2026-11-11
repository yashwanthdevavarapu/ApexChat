package com.apexchat.common;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sliding-window rate limiter keyed by username.
 *
 * <p>Limits apply per user rather than per session, so opening extra tabs does not raise a quota.
 * Idle keys are swept periodically so the map does not grow with every user ever seen.
 */
public class RateLimiter {

  private static final int SWEEP_EVERY = 1024;

  private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
  private final AtomicLong calls = new AtomicLong();
  private final Clock clock;
  private final int maxEvents;
  private final long windowMillis;

  public RateLimiter(int maxEvents, Duration window, Clock clock) {
    this.clock = clock;
    this.maxEvents = maxEvents;
    this.windowMillis = window.toMillis();
  }

  /**
   * Records an attempt for {@code key}.
   *
   * @throws RateLimitExceededException when the window is already full
   */
  public void acquire(String key) {
    if (!tryAcquire(key)) {
      throw new RateLimitExceededException();
    }
  }

  /** Records an attempt for {@code key} and reports whether it was within the limit. */
  public boolean tryAcquire(String key) {
    long now = clock.millis();
    if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
      sweep(now);
    }
    Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      evictExpired(window, now);
      if (window.size() >= maxEvents) {
        return false;
      }
      window.addLast(now);
      return true;
    }
  }

  int trackedKeys() {
    return windows.size();
  }

  private void sweep(long now) {
    windows
        .entrySet()
        .removeIf(
            e -> {
              Deque<Long> window = e.getValue();
              synchronized (window) {
                evictExpired(window, now);
                return window.isEmpty();
              }
            });
  }

  private void evictExpired(Deque<Long> window, long now) {
    while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
      window.pollFirst();
    }
  }
}
