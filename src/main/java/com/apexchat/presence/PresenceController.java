package com.apexchat.presence;

import java.time.Clock;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the current roster.
 *
 * <p>A client subscribes to {@code /topic/presence} for changes <em>and</em> to {@code
 * /app/presence} for an immediate snapshot, which closes the gap between connecting and receiving
 * the first broadcast.
 */
@RestController
public class PresenceController {

  private final PresenceService presence;
  private final Clock clock;

  public PresenceController(PresenceService presence, Clock clock) {
    this.presence = presence;
    this.clock = clock;
  }

  @SubscribeMapping("/presence")
  public PresenceEvent snapshotOnSubscribe() {
    return snapshot();
  }

  @GetMapping("/api/presence")
  public PresenceEvent snapshot() {
    return new PresenceEvent(
        PresenceEvent.Type.SNAPSHOT, null, presence.onlineUsers(), clock.instant());
  }
}
