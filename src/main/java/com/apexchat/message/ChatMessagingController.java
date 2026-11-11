package com.apexchat.message;

import com.apexchat.common.ChatException;
import com.apexchat.common.ErrorEvent;
import com.apexchat.common.InvalidRequestException;
import com.apexchat.user.UserService;
import java.security.Principal;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * Inbound STOMP commands. Clients SEND to {@code /app/...}; results fan out through the broker.
 *
 * <table>
 *   <caption>Destinations</caption>
 *   <tr><th>SEND to</th><th>Effect</th></tr>
 *   <tr><td>/app/rooms/{slug}/send</td><td>broadcast on /topic/rooms/{slug}</td></tr>
 *   <tr><td>/app/rooms/{slug}/typing</td><td>broadcast on /topic/rooms/{slug}/typing</td></tr>
 *   <tr><td>/app/dm/{user}/send</td><td>deliver to /user/queue/dm of both parties</td></tr>
 *   <tr><td>/app/dm/{user}/typing</td><td>deliver to /user/queue/typing of the recipient</td></tr>
 * </table>
 */
@Controller
public class ChatMessagingController {

  private static final Logger log = LoggerFactory.getLogger(ChatMessagingController.class);
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{2,40}$");

  private final MessageService messageService;
  private final SimpMessagingTemplate broker;

  public ChatMessagingController(MessageService messageService, SimpMessagingTemplate broker) {
    this.messageService = messageService;
    this.broker = broker;
  }

  @MessageMapping("/rooms/{slug}/send")
  public void sendToRoom(
      @DestinationVariable String slug, @Payload SendMessageRequest request, Principal user) {
    messageService.sendToRoom(user.getName(), slug, request.content());
  }

  @MessageMapping("/rooms/{slug}/typing")
  public void typingInRoom(
      @DestinationVariable String slug, @Payload TypingEvent.Request request, Principal user) {
    if (!SLUG.matcher(slug).matches()) {
      throw new InvalidRequestException("INVALID_ROOM", "Invalid room");
    }
    broker.convertAndSend(
        MessageService.roomTopic(slug) + "/typing",
        new TypingEvent(user.getName(), slug, request.typing()));
  }

  @MessageMapping("/dm/{username}/send")
  public void sendDirect(
      @DestinationVariable String username, @Payload SendMessageRequest request, Principal user) {
    messageService.sendDirect(user.getName(), username, request.content());
  }

  @MessageMapping("/dm/{username}/typing")
  public void typingInDirect(
      @DestinationVariable String username, @Payload TypingEvent.Request request, Principal user) {
    broker.convertAndSendToUser(
        UserService.normalize(username),
        "/queue/typing",
        new TypingEvent(user.getName(), null, request.typing()));
  }

  /** Expected failures go back only to the session that caused them. */
  @MessageExceptionHandler
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorEvent handleChatException(ChatException ex) {
    return ErrorEvent.from(ex);
  }

  @MessageExceptionHandler
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorEvent handleUnexpected(Exception ex) {
    log.warn("Rejected STOMP message: {}", ex.toString());
    return new ErrorEvent("BAD_REQUEST", "The server could not process that message");
  }
}
