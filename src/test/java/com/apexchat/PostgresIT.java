package com.apexchat;

import static org.assertj.core.api.Assertions.assertThat;

import com.apexchat.message.MessageService;
import com.apexchat.room.RoomService;
import com.apexchat.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the Flyway migrations and the persistence path against a real PostgreSQL 16. Skipped
 * automatically when Docker is unavailable (CI always has it).
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired RoomService rooms;
  @Autowired UserService users;
  @Autowired MessageService messages;

  @Test
  void migrationsSeedRoomsAndMessagesRoundTrip() {
    assertThat(rooms.listRooms()).extracting("slug").contains("general", "engineering", "random");

    users.register("pg_alice", "postgres-pass");
    users.register("pg_bob", "postgres-pass");
    messages.sendToRoom("pg_alice", "general", "hello from postgres");
    messages.sendDirect("pg_alice", "pg_bob", "dm one");
    messages.sendDirect("pg_bob", "pg_alice", "dm two");

    assertThat(messages.roomHistory("general", null, null))
        .extracting("content")
        .contains("hello from postgres");
    assertThat(messages.directHistory("pg_bob", "pg_alice", null, null))
        .extracting("content")
        .containsExactly("dm one", "dm two");
  }
}
