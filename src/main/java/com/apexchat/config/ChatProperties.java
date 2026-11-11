package com.apexchat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the chat server, bound from {@code apexchat.*} in application.yml.
 *
 * @param maxMessageLength maximum characters allowed in a single message
 * @param historyPageSize default number of messages returned by history endpoints
 * @param maxHistoryPageSize upper bound a client may request per history page
 * @param allowedOrigins extra origins allowed to open the WebSocket (same-origin is always allowed)
 * @param rateLimit per-user send throttle
 */
@Validated
@ConfigurationProperties(prefix = "apexchat")
public record ChatProperties(
    @DefaultValue("2000") @Min(1) @Max(MESSAGE_COLUMN_LENGTH) int maxMessageLength,
    @DefaultValue("50") @Min(1) int historyPageSize,
    @DefaultValue("100") @Min(1) int maxHistoryPageSize,
    @DefaultValue List<String> allowedOrigins,
    @DefaultValue RateLimit rateLimit) {

  /** Size of chat_message.content (V1 migration); the configurable limit can never exceed it. */
  public static final int MESSAGE_COLUMN_LENGTH = 2000;

  /**
   * Sliding-window send limit applied per user across all of their sessions.
   *
   * @param maxMessages messages allowed inside one window
   * @param window length of the sliding window
   */
  public record RateLimit(
      @DefaultValue("20") @Min(1) int maxMessages, @DefaultValue("10s") Duration window) {}
}
