package com.apexchat.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.apexchat.common.ConflictException;
import com.apexchat.common.InvalidRequestException;
import com.apexchat.common.NotFoundException;
import com.apexchat.support.MutableClock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

  @Mock private ChatRoomRepository rooms;
  @Mock private SimpMessagingTemplate broker;
  private RoomService service;

  @BeforeEach
  void setUp() {
    service = new RoomService(rooms, broker, new MutableClock(NOW));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "General            | general",
        "Design Crit!       | design-crit",
        "  --hello  world-- | hello-world",
        "Café Résumé        | cafe-resume",
        "Q3 2026 Planning   | q3-2026-planning",
        "!!!                | ''",
      })
  void slugifiesNames(String name, String expected) {
    assertThat(RoomService.slugify(name)).isEqualTo(expected);
  }

  @Test
  void slugIsCappedAtFortyCharactersWithoutTrailingDash() {
    String slug = RoomService.slugify("a".repeat(39) + " bcd");
    assertThat(slug).hasSizeLessThanOrEqualTo(40).doesNotEndWith("-");
  }

  @Test
  void createPersistsAndBroadcastsTheRoom() {
    when(rooms.existsBySlug("launch-party")).thenReturn(false);
    when(rooms.saveAndFlush(any(ChatRoom.class))).thenAnswer(inv -> inv.getArgument(0));

    RoomDto dto = service.create(new CreateRoomRequest("Launch Party", "  🎉  "), "alice");

    assertThat(dto.slug()).isEqualTo("launch-party");
    assertThat(dto.name()).isEqualTo("Launch Party");
    assertThat(dto.description()).isEqualTo("🎉");
    assertThat(dto.createdBy()).isEqualTo("alice");
    assertThat(dto.createdAt()).isEqualTo(NOW);
    verify(broker).convertAndSend(RoomService.ROOMS_TOPIC, dto);
  }

  @Test
  void createRejectsDuplicates() {
    when(rooms.existsBySlug("general")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new CreateRoomRequest("GENERAL", null), "alice"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("#general");
    verify(rooms, never()).saveAndFlush(any());
  }

  @Test
  void createRejectsNamesWithoutLettersOrDigits() {
    assertThatThrownBy(() -> service.create(new CreateRoomRequest("!!", null), "alice"))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void requireThrowsForUnknownRooms() {
    when(rooms.findBySlug("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.require("nope"))
        .isInstanceOf(NotFoundException.class)
        .extracting("code")
        .isEqualTo("ROOM_NOT_FOUND");
  }

  @Test
  void concurrentDuplicateHittingTheUniqueConstraintIsStillAConflict() {
    when(rooms.existsBySlug("design-crit")).thenReturn(false); // both racers pass the pre-check
    when(rooms.saveAndFlush(any(ChatRoom.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_slug"));

    assertThatThrownBy(() -> service.create(new CreateRoomRequest("Design Crit", null), "bob"))
        .isInstanceOf(ConflictException.class)
        .extracting("code")
        .isEqualTo("ROOM_EXISTS");
    org.mockito.Mockito.verifyNoInteractions(broker);
  }
}
