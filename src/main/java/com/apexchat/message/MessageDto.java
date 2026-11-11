package com.apexchat.message;

import java.time.Instant;

/**
 * Wire format for a chat message.
 *
 * @param room slug of the room for channel messages, {@code null} for direct messages
 * @param recipient username of the recipient for direct messages, {@code null} for rooms
 */
public record MessageDto(
    long id, String room, String sender, String recipient, String content, Instant sentAt) {

  static MessageDto from(ChatMessage m, String roomSlug) {
    return new MessageDto(
        m.getId(), roomSlug, m.getSender(), m.getRecipient(), m.getContent(), m.getSentAt());
  }
}
