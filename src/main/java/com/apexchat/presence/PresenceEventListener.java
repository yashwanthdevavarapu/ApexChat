package com.apexchat.presence;

import java.security.Principal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Turns STOMP session lifecycle events into presence updates on {@code /topic/presence}. */
@Component
public class PresenceEventListener {

  public static final String PRESENCE_TOPIC = "/topic/presence";
  private static final Logger log = LoggerFactory.getLogger(PresenceEventListener.class);

  private final PresenceService presence;
  private final SimpMessagingTemplate broker;
  private final Clock clock;

  public PresenceEventListener(
      PresenceService presence, SimpMessagingTemplate broker, Clock clock) {
    this.presence = presence;
    this.broker = broker;
    this.clock = clock;
  }

  @EventListener
  public void onConnected(SessionConnectedEvent event) {
    Principal user = event.getUser();
    String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
    if (user == null || sessionId == null) {
      return;
    }
    log.debug("STOMP session {} connected for {}", sessionId, user.getName());
    if (presence.connect(user.getName(), sessionId)) {
      publish(PresenceEvent.Type.ONLINE, user.getName());
    }
  }

  @EventListener
  public void onDisconnected(SessionDisconnectEvent event) {
    Principal user = event.getUser();
    if (user == null) {
      return;
    }
    log.debug("STOMP session {} closed for {}", event.getSessionId(), user.getName());
    if (presence.disconnect(user.getName(), event.getSessionId())) {
      publish(PresenceEvent.Type.OFFLINE, user.getName());
    }
  }

  private void publish(PresenceEvent.Type type, String username) {
    broker.convertAndSend(
        PRESENCE_TOPIC, new PresenceEvent(type, username, presence.onlineUsers(), clock.instant()));
  }
}
