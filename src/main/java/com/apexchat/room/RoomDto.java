package com.apexchat.room;

import java.time.Instant;

/** Public view of a room, sent over REST and broadcast on {@code /topic/rooms}. */
public record RoomDto(
    String slug, String name, String description, String createdBy, Instant createdAt) {

  public static RoomDto from(ChatRoom room) {
    return new RoomDto(
        room.getSlug(),
        room.getName(),
        room.getDescription(),
        room.getCreatedBy(),
        room.getCreatedAt());
  }
}
