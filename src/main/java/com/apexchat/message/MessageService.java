package com.apexchat.message;

import com.apexchat.common.InvalidRequestException;
import com.apexchat.common.NotFoundException;
import com.apexchat.common.RateLimiter;
import com.apexchat.config.ChatProperties;
import com.apexchat.room.ChatRoom;
import com.apexchat.room.RoomService;
import com.apexchat.user.UserService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates, persists and fans out chat messages.
 *
 * <p>Messages are written first and broadcast second, so anything a client receives live is also
 * available from the history API (see docs/adr/0004).
 */
@Service
public class MessageService {

  public static final String DM_QUEUE = "/queue/dm";

  private final ChatMessageRepository messages;
  private final RoomService roomService;
  private final UserService userService;
  private final RateLimiter rateLimiter;
  private final SimpMessagingTemplate broker;
  private final ChatProperties properties;
  private final Clock clock;
  private final Counter roomMessages;
  private final Counter directMessages;

  public MessageService(
      ChatMessageRepository messages,
      RoomService roomService,
      UserService userService,
      RateLimiter rateLimiter,
      SimpMessagingTemplate broker,
      ChatProperties properties,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.messages = messages;
    this.roomService = roomService;
    this.userService = userService;
    this.rateLimiter = rateLimiter;
    this.broker = broker;
    this.properties = properties;
    this.clock = clock;
    this.roomMessages = messageCounter(meterRegistry, "room");
    this.directMessages = messageCounter(meterRegistry, "direct");
  }

  public static String roomTopic(String slug) {
    return "/topic/rooms/" + slug;
  }

  public MessageDto sendToRoom(String sender, String slug, String content) {
    String body = validateContent(content);
    ChatRoom room = roomService.require(slug);
    rateLimiter.acquire(sender);

    ChatMessage saved =
        messages.save(ChatMessage.toRoom(room.getId(), sender, body, clock.instant()));
    MessageDto dto = MessageDto.from(saved, room.getSlug());
    broker.convertAndSend(roomTopic(room.getSlug()), dto);
    roomMessages.increment();
    return dto;
  }

  public MessageDto sendDirect(String sender, String recipient, String content) {
    String body = validateContent(content);
    String to = requireOtherUser(sender, recipient);
    rateLimiter.acquire(sender);

    ChatMessage saved = messages.save(ChatMessage.direct(sender, to, body, clock.instant()));
    MessageDto dto = MessageDto.from(saved, null);
    // Deliver to every session of both participants so all of the sender's tabs stay in sync
    broker.convertAndSendToUser(to, DM_QUEUE, dto);
    broker.convertAndSendToUser(sender, DM_QUEUE, dto);
    directMessages.increment();
    return dto;
  }

  @Transactional(readOnly = true)
  public List<MessageDto> roomHistory(String slug, Long before, Integer limit) {
    ChatRoom room = roomService.require(slug);
    var page =
        messages.findByRoomIdAndIdLessThanOrderByIdDesc(
            room.getId(), cursor(before), pageOf(limit));
    return chronological(page.stream().map(m -> MessageDto.from(m, room.getSlug())).toList());
  }

  @Transactional(readOnly = true)
  public List<MessageDto> directHistory(String me, String other, Long before, Integer limit) {
    String them = requireOtherUser(me, other);
    var page = messages.findConversation(me, them, cursor(before), pageOf(limit));
    return chronological(page.stream().map(m -> MessageDto.from(m, null)).toList());
  }

  String validateContent(String content) {
    String body = content == null ? "" : content.strip();
    if (body.isEmpty()) {
      throw new InvalidRequestException("EMPTY_MESSAGE", "Message cannot be empty");
    }
    if (body.length() > properties.maxMessageLength()) {
      throw new InvalidRequestException(
          "MESSAGE_TOO_LONG",
          "Messages are limited to " + properties.maxMessageLength() + " characters");
    }
    return body;
  }

  private String requireOtherUser(String me, String other) {
    String them = UserService.normalize(other);
    if (them.equals(me)) {
      throw new InvalidRequestException("SELF_MESSAGE", "You cannot message yourself");
    }
    if (!userService.exists(them)) {
      throw NotFoundException.user(them);
    }
    return them;
  }

  private static long cursor(Long before) {
    return before == null ? Long.MAX_VALUE : before;
  }

  private PageRequest pageOf(Integer limit) {
    int size = limit == null ? properties.historyPageSize() : limit;
    return PageRequest.of(0, Math.max(1, Math.min(size, properties.maxHistoryPageSize())));
  }

  private static List<MessageDto> chronological(List<MessageDto> newestFirst) {
    var list = new ArrayList<>(newestFirst);
    Collections.reverse(list);
    return list;
  }

  private static Counter messageCounter(MeterRegistry registry, String channel) {
    return Counter.builder("apexchat.messages.sent")
        .description("Messages accepted by the server")
        .tag("channel", channel)
        .register(registry);
  }
}
