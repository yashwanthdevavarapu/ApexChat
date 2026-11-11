package com.apexchat.presence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Tracks which users are online by counting their live STOMP sessions.
 *
 * <p>A user with three tabs open has three sessions; they go offline only when the last one closes.
 * Mutations are serialized on this instance (presence changes are rare, so contention is
 * negligible) and every transition bumps a monotonically increasing {@link Roster#version()} that
 * clients use to discard stale, out-of-order presence frames. Closed session ids are remembered
 * briefly so a disconnect processed <em>before</em> its connect event cannot leave a ghost session
 * behind. See docs/adr/0006.
 */
@Service
public class PresenceService {

  static final long CLOSED_SESSION_TTL_MS = 60_000;
  private static final int PURGE_EVERY = 256;

  private final Map<String, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
  private final Map<String, Long> closedSessions = new HashMap<>(); // guarded by this
  private final Clock clock;
  private long version; // guarded by this
  private long disconnects; // guarded by this

  /** The online users as of a specific presence version. */
  public record Roster(List<String> online, long version) {}

  public PresenceService(MeterRegistry registry, Clock clock) {
    this.clock = clock;
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
  public synchronized boolean connect(String username, String sessionId) {
    if (closedSessions.containsKey(sessionId)) {
      return false; // the disconnect for this session was already processed
    }
    Set<String> sessions =
        sessionsByUser.computeIfAbsent(username, u -> ConcurrentHashMap.newKeySet());
    boolean cameOnline = sessions.isEmpty();
    sessions.add(sessionId);
    if (cameOnline) {
      version++;
    }
    return cameOnline;
  }

  /**
   * Removes a session. Safe to call more than once for the same session.
   *
   * @return {@code true} if that was the user's last session, i.e. they just went offline
   */
  public synchronized boolean disconnect(String username, String sessionId) {
    rememberClosed(sessionId);
    Set<String> sessions = sessionsByUser.get(username);
    if (sessions == null || !sessions.remove(sessionId) || !sessions.isEmpty()) {
      return false;
    }
    sessionsByUser.remove(username);
    version++;
    return true;
  }

  public synchronized Roster roster() {
    return new Roster(onlineUsers(), version);
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

  synchronized int rememberedClosedSessions() {
    return closedSessions.size();
  }

  private void rememberClosed(String sessionId) {
    long now = clock.millis();
    closedSessions.put(sessionId, now);
    if (++disconnects % PURGE_EVERY == 0) {
      closedSessions.values().removeIf(closedAt -> now - closedAt > CLOSED_SESSION_TTL_MS);
    }
  }
}
