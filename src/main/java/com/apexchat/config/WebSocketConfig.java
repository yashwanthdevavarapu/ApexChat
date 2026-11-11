package com.apexchat.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket configuration.
 *
 * <ul>
 *   <li>{@code /ws} - the single WebSocket handshake endpoint
 *   <li>{@code /app/**} - client SENDs routed to {@code @MessageMapping} handlers
 *   <li>{@code /topic/**} - fan-out destinations (rooms, presence, typing)
 *   <li>{@code /user/queue/**} - per-user destinations (direct messages, errors)
 * </ul>
 *
 * <p>See docs/adr/0002 and 0003 for why STOMP and the in-memory simple broker were chosen.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  /** Server and client heartbeat interval in milliseconds. */
  static final long HEARTBEAT_MS = 10_000;

  private final ChatProperties properties;
  private TaskScheduler messageBrokerTaskScheduler;

  public WebSocketConfig(ChatProperties properties) {
    this.properties = properties;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    var endpoint = registry.addEndpoint("/ws");
    if (!properties.allowedOrigins().isEmpty()) {
      endpoint.setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
        .setTaskScheduler(messageBrokerTaskScheduler);
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
    // Deliberately NOT enabling preservePublishOrder / preserveReceiveOrder: in Spring 6.2 either
    // one routes user destinations through per-session ordered templates that fail when a user
    // has several sessions ("Expected mutable SimpMessageHeaderAccessor"), which would break
    // direct messages for anyone with two tabs open. Instead the database id is the ordering
    // authority and clients render by id. See docs/adr/0007-message-ordering.md.
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration
        .setMessageSizeLimit(64 * 1024)
        .setSendBufferSizeLimit(512 * 1024)
        .setSendTimeLimit(15_000);
  }

  /**
   * Reuses the scheduler Spring's broker configuration already defines, as the Spring reference
   * recommends; {@code @Lazy} breaks the cycle with the configuration that creates it.
   */
  @Autowired
  void setMessageBrokerTaskScheduler(
      @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler taskScheduler) {
    this.messageBrokerTaskScheduler = taskScheduler;
  }
}
