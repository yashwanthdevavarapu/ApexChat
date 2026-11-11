package com.apexchat.message;

/**
 * Ephemeral "is typing" signal. Never persisted.
 *
 * @param room room slug, or {@code null} when the signal belongs to a direct conversation
 */
public record TypingEvent(String username, String room, boolean typing) {

  /** Inbound payload: {@code {"typing": true}}. */
  public record Request(boolean typing) {}
}
