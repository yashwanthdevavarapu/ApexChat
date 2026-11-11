package com.apexchat.config;

import static org.springframework.messaging.simp.SimpMessageType.MESSAGE;
import static org.springframework.messaging.simp.SimpMessageType.SUBSCRIBE;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * Authorizes every inbound STOMP frame.
 *
 * <p>{@code @EnableWebSocketSecurity} also requires a valid CSRF token in the STOMP CONNECT
 * headers, which blocks cross-site WebSocket hijacking (see docs/adr/0005).
 */
@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

  @Bean
  AuthorizationManager<Message<?>> messageAuthorizationManager(
      MessageMatcherDelegatingAuthorizationManager.Builder messages) {
    messages
        // CONNECT, DISCONNECT, UNSUBSCRIBE and heartbeats carry no destination
        .nullDestMatcher()
        .authenticated()
        .simpDestMatchers("/app/**")
        .authenticated()
        .simpSubscribeDestMatchers(
            "/topic/rooms",
            "/topic/rooms/*",
            "/topic/rooms/*/typing",
            "/topic/presence",
            "/user/queue/**")
        .authenticated()
        // Clients may never publish straight to the broker or subscribe anywhere else
        .simpTypeMatchers(MESSAGE, SUBSCRIBE)
        .denyAll()
        .anyMessage()
        .denyAll();
    return messages.build();
  }
}
