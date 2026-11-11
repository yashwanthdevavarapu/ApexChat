package com.apexchat.presence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * Tracks which users are online by counting their live STOMP sessions.
 *
 * <p>A user with three tabs open has three sessions; they go offline only when the last one closes.
 * All updates go through {@link ConcurrentHashMap#compute} so concurrent connects and disconnects
 * for the same user are serialized without a global lock (see docs/adr/0006).
 */
@Service
public class PresenceService {

  private final Map<String, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

  public PresenceService(MeterRegistry registry) {
    Gauge.builder("apexchat.presence.online.users", sessionsByUser, Map::size)
        .description("Users with at least one live WebSocket session")
        .register(registry);
    Gauge.builder("apexchat.presence.sessions", this, PresenceService::sessionCount)
        .description("Live authenticated WebSocket sessions")
        .register(registry);
  }

  /**
   * Registers a session.
   *
   * @return {@code true} if this is the user's first session, i.e. they just came online
   */
  public boolean connect(String username, String sessionId) {
    AtomicBoolean cameOnline = new AtomicBoolean();
    sessionsByUser.compute(
        username,
        (user, sessions) -> {
          Set<String> set = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
          cameOnline.set(set.isEmpty());
          set.add(sessionId);
          return set;
        });
    return cameOnline.get();
  }

  /**
   * Removes a session. Safe to call more than once for the same session.
   *
   * @return {@code true} if that was the user's last session, i.e. they just went offline
   */
  public boolean disconnect(String username, String sessionId) {
    AtomicBoolean wentOffline = new AtomicBoolean();
    sessionsByUser.computeIfPresent(
        username,
        (user, sessions) -> {
          if (sessions.remove(sessionId) && sessions.isEmpty()) {
            wentOffline.set(true);
            return null;
          }
          return sessions.isEmpty() ? null : sessions;
        });
    return wentOffline.get();
  }

  public boolean isOnline(String username) {
    return sessionsByUser.containsKey(username);
  }

  public List<String> onlineUsers() {
    return sessionsByUser.keySet().stream().sorted().toList();
  }

  public int sessionCount() {
    return sessionsByUser.values().stream().mapToInt(Set::size).sum();
  }

  public int sessionCount(String username) {
    Set<String> sessions = sessionsByUser.get(username);
    return sessions == null ? 0 : sessions.size();
  }
}
