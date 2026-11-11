package com.apexchat.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A public channel that any signed-in user can read and post to. */
@Entity
@Table(name = "chat_room")
public class ChatRoom {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 40)
  private String slug;

  @Column(nullable = false, length = 60)
  private String name;

  @Column(length = 200)
  private String description;

  @Column(name = "created_by", nullable = false, length = 32)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ChatRoom() {}

  public ChatRoom(
      String slug, String name, String description, String createdBy, Instant createdAt) {
    this.slug = slug;
    this.name = name;
    this.description = description;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
