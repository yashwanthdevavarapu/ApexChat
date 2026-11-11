package com.apexchat.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A persisted message. Exactly one of {@code roomId} (channel message) or {@code recipient} (direct
 * message) is set; the database enforces this with a CHECK constraint.
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "room_id")
  private Long roomId;

  @Column(nullable = false, length = 32)
  private String sender;

  @Column(length = 32)
  private String recipient;

  @Column(nullable = false, length = 2000)
  private String content;

  @Column(name = "sent_at", nullable = false)
  private Instant sentAt;

  protected ChatMessage() {}

  private ChatMessage(
      Long roomId, String sender, String recipient, String content, Instant sentAt) {
    this.roomId = roomId;
    this.sender = sender;
    this.recipient = recipient;
    this.content = content;
    this.sentAt = sentAt;
  }

  public static ChatMessage toRoom(Long roomId, String sender, String content, Instant sentAt) {
    return new ChatMessage(roomId, sender, null, content, sentAt);
  }

  public static ChatMessage direct(
      String sender, String recipient, String content, Instant sentAt) {
    return new ChatMessage(null, sender, recipient, content, sentAt);
  }

  public Long getId() {
    return id;
  }

  public Long getRoomId() {
    return roomId;
  }

  public String getSender() {
    return sender;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getContent() {
    return content;
  }

  public Instant getSentAt() {
    return sentAt;
  }
}
