package com.apexchat.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.apexchat.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PresenceServiceTest {

  private SimpleMeterRegistry registry;
  private PresenceService presence;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    presence = new PresenceService(registry, clock);
  }

  @Test
  void firstSessionBringsUserOnline() {
    assertThat(presence.connect("alice", "s1")).isTrue();
    assertThat(presence.isOnline("alice")).isTrue();
    assertThat(presence.onlineUsers()).containsExactly("alice");
  }

  @Test
  void additionalSessionsDoNotReannounce() {
    presence.connect("alice", "s1");
    assertThat(presence.connect("alice", "s2")).isFalse();
    assertThat(presence.sessionCount()).isEqualTo(2);
  }

  @Test
  void userStaysOnlineUntilLastSessionCloses() {
    presence.connect("alice", "s1");
    presence.connect("alice", "s2");

    assertThat(presence.disconnect("alice", "s1")).isFalse();
    assertThat(presence.isOnline("alice")).isTrue();

    assertThat(presence.disconnect("alice", "s2")).isTrue();
    assertThat(presence.isOnline("alice")).isFalse();
    assertThat(presence.onlineUsers()).isEmpty();
  }

  @Test
  void disconnectIsIdempotent() {
    presence.connect("alice", "s1");
    assertThat(presence.disconnect("alice", "s1")).isTrue();
    // Spring may publish SessionDisconnectEvent more than once for the same session
    assertThat(presence.disconnect("alice", "s1")).isFalse();
    assertThat(presence.disconnect("nobody", "s9")).isFalse();
  }

  @Test
  void unknownSessionDoesNotTakeUserOffline() {
    presence.connect("alice", "s1");
    assertThat(presence.disconnect("alice", "not-a-session")).isFalse();
    assertThat(presence.isOnline("alice")).isTrue();
  }

  @Test
  void rosterIsSorted() {
    presence.connect("zoe", "1");
    presence.connect("adam", "2");
    presence.connect("mia", "3");
    assertThat(presence.onlineUsers()).containsExactly("adam", "mia", "zoe");
  }

  @Test
  void exposesGauges() {
    presence.connect("alice", "s1");
    presence.connect("alice", "s2");
    presence.connect("bob", "s3");

    assertThat(registry.get("apexchat.presence.online.users").gauge().value()).isEqualTo(2);
    assertThat(registry.get("apexchat.presence.sessions").gauge().value()).isEqualTo(3);
  }

  @Test
  void concurrentConnectsAndDisconnectsAnnounceEachTransitionExactlyOnce() throws Exception {
    int threads = 16;
    int rounds = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger online = new AtomicInteger();
    AtomicInteger offline = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();

    for (int t = 0; t < threads; t++) {
      int thread = t;
      futures.add(
          pool.submit(
              () -> {
                start.await();
                for (int r = 0; r < rounds; r++) {
                  String session = thread + "-" + r;
                  if (presence.connect("alice", session)) {
                    online.incrementAndGet();
                  }
                  if (presence.disconnect("alice", session)) {
                    offline.incrementAndGet();
                  }
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();

    // Every ONLINE is matched by exactly one OFFLINE and the user ends offline
    assertThat(online.get()).isPositive().isEqualTo(offline.get());
    assertThat(presence.isOnline("alice")).isFalse();
    assertThat(presence.sessionCount()).isZero();
  }

  @Test
  void disconnectProcessedBeforeConnectDoesNotLeaveAGhostSession() {
    // Spring may handle a socket that dropped immediately in either order
    assertThat(presence.disconnect("alice", "s1")).isFalse();
    assertThat(presence.connect("alice", "s1")).isFalse();
    assertThat(presence.isOnline("alice")).isFalse();
    assertThat(presence.sessionCount()).isZero();
  }

  @Test
  void versionIncreasesOnEveryTransitionOnly() {
    long v0 = presence.roster().version();
    presence.connect("alice", "s1");
    long v1 = presence.roster().version();
    presence.connect("alice", "s2"); // not a transition
    assertThat(presence.roster().version()).isEqualTo(v1);
    presence.disconnect("alice", "s1"); // not a transition
    assertThat(presence.roster().version()).isEqualTo(v1);
    presence.disconnect("alice", "s2");
    assertThat(v1).isGreaterThan(v0);
    assertThat(presence.roster().version()).isGreaterThan(v1);
    assertThat(presence.roster().online()).isEmpty();
  }

  @Test
  void rememberedClosedSessionsArePurgedAfterTheirTtl() {
    for (int i = 0; i < 255; i++) {
      presence.disconnect("u", "old-" + i);
    }
    clock.advance(Duration.ofMillis(PresenceService.CLOSED_SESSION_TTL_MS + 1));
    presence.disconnect("u", "fresh"); // 256th disconnect triggers a purge
    assertThat(presence.rememberedClosedSessions()).isEqualTo(1);
  }
}
