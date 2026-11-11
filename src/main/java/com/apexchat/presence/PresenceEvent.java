package com.apexchat.presence;

import java.time.Instant;
import java.util.List;

/**
 * Broadcast on {@code /topic/presence} whenever someone comes online or goes offline. Carries the
 * full roster so a client that missed an event self-heals on the next one.
 */
public record PresenceEvent(Type type, String username, List<String> online, Instant at) {

  public enum Type {
    SNAPSHOT,
    ONLINE,
    OFFLINE
  }
}
