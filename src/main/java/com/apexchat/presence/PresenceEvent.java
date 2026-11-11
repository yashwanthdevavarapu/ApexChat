package com.apexchat.presence;

import java.time.Instant;
import java.util.List;

/**
 * Broadcast on {@code /topic/presence} whenever someone comes online or goes offline. Carries the
 * full roster so a client that missed an event self-heals on the next one, and a monotonically
 * increasing {@code version} so a client can ignore frames that arrive out of order.
 */
public record PresenceEvent(
    Type type, String username, List<String> online, long version, Instant at) {

  public enum Type {
    SNAPSHOT,
    ONLINE,
    OFFLINE
  }

  static PresenceEvent of(Type type, String username, PresenceService.Roster roster, Instant at) {
    return new PresenceEvent(type, username, roster.online(), roster.version(), at);
  }
}
