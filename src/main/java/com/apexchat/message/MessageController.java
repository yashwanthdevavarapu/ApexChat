package com.apexchat.message;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Message history (keyset pagination) plus an HTTP send endpoint.
 *
 * <p>The HTTP send path exists for integrations and as the request/response baseline in the latency
 * benchmark; the browser client sends over STOMP.
 */
@RestController
@RequestMapping("/api")
public class MessageController {

  private final MessageService messageService;

  public MessageController(MessageService messageService) {
    this.messageService = messageService;
  }

  @GetMapping("/rooms/{slug}/messages")
  public List<MessageDto> roomHistory(
      @PathVariable String slug,
      @RequestParam(required = false) Long before,
      @RequestParam(required = false) Integer limit) {
    return messageService.roomHistory(slug, before, limit);
  }

  @PostMapping("/rooms/{slug}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public MessageDto postToRoom(
      @PathVariable String slug, @RequestBody SendMessageRequest request, Principal user) {
    return messageService.sendToRoom(user.getName(), slug, request.content());
  }

  @GetMapping("/dm/{username}/messages")
  public List<MessageDto> directHistory(
      @PathVariable String username,
      @RequestParam(required = false) Long before,
      @RequestParam(required = false) Integer limit,
      Principal user) {
    return messageService.directHistory(user.getName(), username, before, limit);
  }
}
