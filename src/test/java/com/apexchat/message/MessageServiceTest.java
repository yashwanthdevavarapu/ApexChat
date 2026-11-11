package com.apexchat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.apexchat.common.InvalidRequestException;
import com.apexchat.common.NotFoundException;
import com.apexchat.common.RateLimitExceededException;
import com.apexchat.common.RateLimiter;
import com.apexchat.room.ChatRoom;
import com.apexchat.room.RoomService;
import com.apexchat.support.MutableClock;
import com.apexchat.support.TestProperties;
import com.apexchat.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

  @Mock private ChatMessageRepository messages;
  @Mock private RoomService roomService;
  @Mock private UserService userService;
  @Mock private SimpMessagingTemplate broker;
  @Mock private RateLimiter rateLimiter;

  private SimpleMeterRegistry meters;
  private MessageService service;
  private final AtomicLong ids = new AtomicLong(100);

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    service =
        new MessageService(
            messages,
            roomService,
            userService,
            rateLimiter,
            broker,
            TestProperties.defaults(),
            new MutableClock(NOW),
            meters);
  }

  private void savesAssignIds() {
    when(messages.save(any(ChatMessage.class)))
        .thenAnswer(
            inv -> {
              ChatMessage m = inv.getArgument(0);
              ReflectionTestUtils.setField(m, "id", ids.incrementAndGet());
              return m;
            });
  }

  private ChatRoom room(long id, String slug) {
    ChatRoom room = new ChatRoom(slug, slug, "", "system", NOW);
    ReflectionTestUtils.setField(room, "id", id);
    return room;
  }

  @Test
  void roomMessageIsTrimmedPersistedThenBroadcast() {
    savesAssignIds();
    when(roomService.require("general")).thenReturn(room(1, "general"));

    MessageDto dto = service.sendToRoom("alice", "general", "   hello world \n");

    assertThat(dto.content()).isEqualTo("hello world");
    assertThat(dto.room()).isEqualTo("general");
    assertThat(dto.recipient()).isNull();
    assertThat(dto.sentAt()).isEqualTo(NOW);
    assertThat(dto.id()).isEqualTo(101);
    verify(rateLimiter).acquire("alice");
    verify(broker).convertAndSend("/topic/rooms/general", dto);
    assertThat(meters.get("apexchat.messages.sent").tag("channel", "room").counter().count())
        .isEqualTo(1);
  }

  @Test
  void blankMessagesAreRejectedBeforeTouchingAnything() {
    assertThatThrownBy(() -> service.sendToRoom("alice", "general", "  \n\t "))
        .isInstanceOf(InvalidRequestException.class)
        .extracting("code")
        .isEqualTo("EMPTY_MESSAGE");
    assertThatThrownBy(() -> service.sendToRoom("alice", "general", null))
        .isInstanceOf(InvalidRequestException.class);
    verifyNoInteractions(messages, broker, rateLimiter, roomService);
  }

  @Test
  void overlongMessagesAreRejected() {
    assertThatThrownBy(() -> service.sendToRoom("alice", "general", "x".repeat(2001)))
        .isInstanceOf(InvalidRequestException.class)
        .extracting("code")
        .isEqualTo("MESSAGE_TOO_LONG");
    assertThat(service.validateContent("x".repeat(2000))).hasSize(2000);
  }

  @Test
  void unknownRoomDoesNotConsumeRateLimit() {
    when(roomService.require("ghost")).thenThrow(NotFoundException.room("ghost"));
    assertThatThrownBy(() -> service.sendToRoom("alice", "ghost", "hi"))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(rateLimiter, messages, broker);
  }

  @Test
  void rateLimitedMessagesAreNotPersisted() {
    when(roomService.require("general")).thenReturn(room(1, "general"));
    org.mockito.Mockito.doThrow(new RateLimitExceededException())
        .when(rateLimiter)
        .acquire("alice");

    assertThatThrownBy(() -> service.sendToRoom("alice", "general", "spam"))
        .isInstanceOf(RateLimitExceededException.class);
    verify(messages, never()).save(any());
    verifyNoInteractions(broker);
  }

  @Test
  void directMessageIsDeliveredToBothParticipants() {
    savesAssignIds();
    when(userService.exists("bob")).thenReturn(true);

    MessageDto dto = service.sendDirect("alice", "  Bob ", "hey");

    assertThat(dto.recipient()).isEqualTo("bob");
    assertThat(dto.room()).isNull();
    verify(broker).convertAndSendToUser("bob", MessageService.DM_QUEUE, dto);
    verify(broker).convertAndSendToUser("alice", MessageService.DM_QUEUE, dto);
    verify(broker, never()).convertAndSend(anyString(), any(Object.class));
  }

  @Test
  void directMessageToSelfIsRejected() {
    assertThatThrownBy(() -> service.sendDirect("alice", "ALICE", "hi me"))
        .isInstanceOf(InvalidRequestException.class)
        .extracting("code")
        .isEqualTo("SELF_MESSAGE");
  }

  @Test
  void directMessageToUnknownUserIsRejected() {
    when(userService.exists("ghost")).thenReturn(false);
    assertThatThrownBy(() -> service.sendDirect("alice", "ghost", "boo"))
        .isInstanceOf(NotFoundException.class)
        .extracting("code")
        .isEqualTo("USER_NOT_FOUND");
    verifyNoInteractions(messages, broker);
  }

  @Test
  void roomHistoryIsReturnedOldestFirstWithClampedPageSize() {
    when(roomService.require("general")).thenReturn(room(7, "general"));
    ChatMessage newer = ChatMessage.toRoom(7L, "bob", "second", NOW.plusSeconds(5));
    ChatMessage older = ChatMessage.toRoom(7L, "alice", "first", NOW);
    ReflectionTestUtils.setField(newer, "id", 2L);
    ReflectionTestUtils.setField(older, "id", 1L);
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    when(messages.findByRoomIdAndIdLessThanOrderByIdDesc(eq(7L), eq(Long.MAX_VALUE), any()))
        .thenReturn(List.of(newer, older));

    List<MessageDto> history = service.roomHistory("general", null, 10_000);

    assertThat(history).extracting(MessageDto::content).containsExactly("first", "second");
    verify(messages).findByRoomIdAndIdLessThanOrderByIdDesc(eq(7L), anyLong(), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  void historyUsesCursorAndDefaultPageSize() {
    when(roomService.require("general")).thenReturn(room(7, "general"));
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    when(messages.findByRoomIdAndIdLessThanOrderByIdDesc(eq(7L), eq(42L), page.capture()))
        .thenReturn(List.of());

    assertThat(service.roomHistory("general", 42L, null)).isEmpty();
    assertThat(page.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  void directHistoryQueriesTheConversationFromBothSides() {
    when(userService.exists("bob")).thenReturn(true);
    ChatMessage m = ChatMessage.direct("bob", "alice", "yo", NOW);
    ReflectionTestUtils.setField(m, "id", 5L);
    when(messages.findConversation(eq("alice"), eq("bob"), eq(Long.MAX_VALUE), any()))
        .thenReturn(List.of(m));

    assertThat(service.directHistory("alice", "Bob", null, null))
        .singleElement()
        .satisfies(
            dto -> {
              assertThat(dto.sender()).isEqualTo("bob");
              assertThat(dto.recipient()).isEqualTo("alice");
            });
  }

  @Test
  void roomTopicFormat() {
    assertThat(MessageService.roomTopic("random")).isEqualTo("/topic/rooms/random");
  }
}
