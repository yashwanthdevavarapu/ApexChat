package com.apexchat.presence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    presence = new PresenceService(registry);
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
}
