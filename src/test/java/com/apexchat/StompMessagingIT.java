package com.apexchat;

import static com.apexchat.support.AuthenticatedStompClient.subscribe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apexchat.common.ErrorEvent;
import com.apexchat.message.MessageDto;
import com.apexchat.message.SendMessageRequest;
import com.apexchat.message.TypingEvent;
import com.apexchat.presence.PresenceEvent;
import com.apexchat.presence.PresenceService;
import com.apexchat.support.AuthenticatedStompClient;
import com.apexchat.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompSession;

/**
 * Full-stack STOMP tests: real server, real WebSocket transport, real Spring Security.
 *
 * <p>SUBSCRIBE frames are processed asynchronously, so before acting each test proves its
 * subscriptions are live by publishing <em>probe</em> frames until one arrives. Assertions match on
 * real content, so stray probes never cause false results.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompMessagingIT {

  private static final String PASSWORD = "integration-pass";
  private static final String PROBE = "__probe__";
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final long WAIT_SECONDS = 5;

  @LocalServerPort int port;
  @Autowired UserService users;
  @Autowired PresenceService presence;
  @Autowired SimpMessagingTemplate broker;

  private final List<StompSession> sessions = new ArrayList<>();

  @AfterEach
  void disconnectAll() {
    sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
  }

  // ------------------------------------------------------------------ tests

  @Test
  void roomMessagesAreBroadcastToEverySubscriber() throws Exception {
    String alice = newUser();
    String bob = newUser();
    StompSession aliceSession = connect(alice);
    StompSession bobSession = connect(bob);
    var aliceInbox = roomInbox(aliceSession, "general");
    var bobInbox = roomInbox(bobSession, "general");

    aliceSession.send("/app/rooms/general/send", new SendMessageRequest("  hello team  "));

    MessageDto received = pollFor(bobInbox, m -> alice.equals(m.sender()));
    assertThat(received.content()).isEqualTo("hello team");
    assertThat(received.room()).isEqualTo("general");
    assertThat(received.id()).isPositive();
    assertThat(pollFor(aliceInbox, m -> m.id() == received.id())).isEqualTo(received);
  }

  @Test
  void aBurstOfMessagesIsDeliveredExactlyOnceWithUniqueIds() throws Exception {
    String alice = newUser();
    StompSession session = connect(alice);
    var inbox = roomInbox(session, "random");

    for (int i = 0; i < 15; i++) {
      session.send("/app/rooms/random/send", new SendMessageRequest("burst-" + i));
    }

    List<MessageDto> received = new ArrayList<>();
    while (received.size() < 15) {
      received.add(pollFor(inbox, m -> alice.equals(m.sender())));
    }
    received.sort(Comparator.comparingLong(MessageDto::id));
    assertThat(received).extracting(MessageDto::id).doesNotHaveDuplicates();
    assertThat(received)
        .extracting(MessageDto::content)
        .containsExactlyInAnyOrder(
            IntStream.range(0, 15).mapToObj(i -> "burst-" + i).toArray(String[]::new));
    assertThat(pollMatching(inbox, m -> alice.equals(m.sender()), 300))
        .as("no duplicate deliveries")
        .isNull();
  }

  @Test
  void directMessagesReachOnlyTheParticipants() throws Exception {
    String alice = newUser();
    String bob = newUser();
    String eve = newUser();
    StompSession aliceSession = connect(alice);
    StompSession bobSession = connect(bob);
    StompSession eveSession = connect(eve);
    var aliceDms = dmInbox(alice, aliceSession);
    var bobDms = dmInbox(bob, bobSession);
    var eveDms = dmInbox(eve, eveSession);

    aliceSession.send("/app/dm/" + bob + "/send", new SendMessageRequest("secret"));

    MessageDto atBob = pollFor(bobDms, m -> "secret".equals(m.content()));
    assertThat(atBob.sender()).isEqualTo(alice);
    assertThat(atBob.recipient()).isEqualTo(bob);
    assertThat(pollFor(aliceDms, m -> "secret".equals(m.content()))).isEqualTo(atBob);
    assertThat(pollMatching(eveDms, m -> "secret".equals(m.content()), 500)).isNull();
  }

  @Test
  void directMessagesFanOutToEverySessionOfTheRecipient() throws Exception {
    String alice = newUser();
    String bob = newUser();
    StompSession aliceSession = connect(alice);
    StompSession bobLaptop = connect(bob);
    StompSession bobPhone = connect(bob);
    var laptop = dmInbox(bob, bobLaptop);
    var phone = dmInbox(bob, bobPhone);

    aliceSession.send("/app/dm/" + bob + "/send", new SendMessageRequest("both devices"));

    assertThat(pollFor(laptop, m -> "both devices".equals(m.content())).sender()).isEqualTo(alice);
    assertThat(pollFor(phone, m -> "both devices".equals(m.content())).sender()).isEqualTo(alice);
  }

  @Test
  void presenceTracksUsersAcrossMultipleSessions() throws Exception {
    String watcher = newUser();
    String multi = newUser();
    StompSession watcherSession = connect(watcher);
    var events = subscribe(watcherSession, "/topic/presence", PresenceEvent.class);
    awaitReady(
        events,
        () ->
            broker.convertAndSend(
                "/topic/presence",
                new PresenceEvent(PresenceEvent.Type.SNAPSHOT, PROBE, List.of(), Instant.now())));

    StompSession tab1 = connect(multi);
    PresenceEvent online = pollFor(events, e -> multi.equals(e.username()));
    assertThat(online.type()).isEqualTo(PresenceEvent.Type.ONLINE);
    assertThat(online.online()).contains(watcher, multi);

    StompSession tab2 = connect(multi);
    awaitCondition(() -> presence.sessionCount(multi) == 2);
    tab1.disconnect();
    awaitCondition(() -> presence.sessionCount(multi) == 1);
    assertThat(presence.isOnline(multi)).isTrue();
    assertThat(pollMatching(events, e -> multi.equals(e.username()), 500))
        .as("closing one of two tabs must not announce OFFLINE")
        .isNull();

    tab2.disconnect();
    PresenceEvent offline = pollFor(events, e -> multi.equals(e.username()));
    assertThat(offline.type()).isEqualTo(PresenceEvent.Type.OFFLINE);
    assertThat(offline.online()).doesNotContain(multi).contains(watcher);
  }

  @Test
  void subscribingToAppPresenceReturnsAnImmediateSnapshot() throws Exception {
    String alice = newUser();
    StompSession session = connect(alice);
    var snapshot = subscribe(session, "/app/presence", PresenceEvent.class);
    PresenceEvent event = snapshot.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    assertThat(event).isNotNull();
    assertThat(event.type()).isEqualTo(PresenceEvent.Type.SNAPSHOT);
    assertThat(event.online()).contains(alice);
  }

  @Test
  void typingSignalsAreRelayedToTheRoom() throws Exception {
    String alice = newUser();
    String bob = newUser();
    StompSession aliceSession = connect(alice);
    StompSession bobSession = connect(bob);
    var typing = subscribe(bobSession, "/topic/rooms/engineering/typing", TypingEvent.class);
    awaitReady(
        typing,
        () ->
            broker.convertAndSend(
                "/topic/rooms/engineering/typing", new TypingEvent(PROBE, "engineering", false)));

    aliceSession.send("/app/rooms/engineering/typing", new TypingEvent.Request(true));

    TypingEvent event = pollFor(typing, e -> alice.equals(e.username()));
    assertThat(event.typing()).isTrue();
    assertThat(event.room()).isEqualTo("engineering");
  }

  @Test
  void invalidCommandsReportAnErrorOnlyToTheSender() throws Exception {
    String alice = newUser();
    StompSession session = connect(alice);
    var errors = errorInbox(alice, session);

    session.send("/app/rooms/general/send", new SendMessageRequest("   "));
    assertThat(pollFor(errors, e -> "EMPTY_MESSAGE".equals(e.code()))).isNotNull();

    session.send("/app/rooms/does-not-exist/send", new SendMessageRequest("hi"));
    assertThat(pollFor(errors, e -> "ROOM_NOT_FOUND".equals(e.code()))).isNotNull();

    session.send("/app/dm/" + alice + "/send", new SendMessageRequest("me"));
    assertThat(pollFor(errors, e -> "SELF_MESSAGE".equals(e.code()))).isNotNull();
  }

  @Test
  void floodingIsRateLimited() throws Exception {
    String alice = newUser();
    StompSession session = connect(alice);
    var errors = errorInbox(alice, session);

    for (int i = 0; i < 25; i++) {
      session.send("/app/rooms/random/send", new SendMessageRequest("spam " + i));
    }
    assertThat(pollFor(errors, e -> "RATE_LIMITED".equals(e.code())).message())
        .contains("too quickly");
  }

  @Test
  void connectWithoutCsrfTokenIsRejected() throws Exception {
    String alice = newUser();
    var client = new AuthenticatedStompClient(port).login(alice, PASSWORD);
    assertThatThrownBy(() -> client.connect(false))
        .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    assertThat(presence.isOnline(alice)).isFalse();
  }

  @Test
  void anonymousHandshakeIsRejected() {
    var anonymous = new AuthenticatedStompClient(port);
    assertThatThrownBy(anonymous::connect)
        .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
  }

  @Test
  void subscribingOutsideTheAllowListIsRefusedAndTheSessionClosed() throws Exception {
    String alice = newUser();
    var client = new AuthenticatedStompClient(port).login(alice, PASSWORD);
    StompSession snoop = client.connect();
    sessions.add(snoop);

    // A raw, already-resolved user queue is not on the allow-list (only /user/queue/** is)
    var rawQueue = subscribe(snoop, "/queue/dm-user" + alice, MessageDto.class);

    var error = client.errors().poll(WAIT_SECONDS, TimeUnit.SECONDS);
    assertThat(error).as("server answers with a STOMP ERROR frame").isNotNull();
    // The reason is intentionally not leaked to the client
    assertThat(error.getFirst("message")).startsWith("Failed to send message");
    awaitCondition(() -> !snoop.isConnected());
    broker.convertAndSend("/queue/dm-user" + alice, probeMessage());
    assertThat(rawQueue.poll(300, TimeUnit.MILLISECONDS)).isNull();
  }

  // ---------------------------------------------------------------- helpers

  private String newUser() {
    String name = "stomp_" + SEQ.incrementAndGet();
    users.register(name, PASSWORD);
    return name;
  }

  /** Connects and waits until the server has registered the session for presence. */
  private StompSession connect(String username) throws Exception {
    int before = presence.sessionCount(username);
    StompSession session = new AuthenticatedStompClient(port).login(username, PASSWORD).connect();
    sessions.add(session);
    awaitCondition(() -> presence.sessionCount(username) > before);
    return session;
  }

  private BlockingQueue<MessageDto> roomInbox(StompSession session, String slug)
      throws InterruptedException {
    var inbox = subscribe(session, "/topic/rooms/" + slug, MessageDto.class);
    awaitReady(inbox, () -> broker.convertAndSend("/topic/rooms/" + slug, probeMessage()));
    return inbox;
  }

  private BlockingQueue<MessageDto> dmInbox(String username, StompSession session)
      throws InterruptedException {
    var inbox = subscribe(session, "/user/queue/dm", MessageDto.class);
    awaitReady(inbox, () -> broker.convertAndSendToUser(username, "/queue/dm", probeMessage()));
    return inbox;
  }

  private BlockingQueue<ErrorEvent> errorInbox(String username, StompSession session)
      throws InterruptedException {
    var inbox = subscribe(session, "/user/queue/errors", ErrorEvent.class);
    awaitReady(
        inbox,
        () -> broker.convertAndSendToUser(username, "/queue/errors", new ErrorEvent(PROBE, PROBE)));
    return inbox;
  }

  private static MessageDto probeMessage() {
    return new MessageDto(0, null, PROBE, null, PROBE, Instant.now());
  }

  /** Publishes probes until the subscription delivers one, then discards what has arrived. */
  private static void awaitReady(BlockingQueue<?> queue, Runnable publishProbe)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos();
    while (queue.isEmpty()) {
      assertThat(System.nanoTime()).as("subscription never became active").isLessThan(deadline);
      publishProbe.run();
      Thread.sleep(25);
    }
    queue.clear();
  }

  private static <T> T pollFor(BlockingQueue<T> queue, Predicate<T> match)
      throws InterruptedException {
    T found = pollMatching(queue, match, TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    assertThat(found).as("expected a matching frame").isNotNull();
    return found;
  }

  private static <T> T pollMatching(BlockingQueue<T> queue, Predicate<T> match, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long remaining;
    while ((remaining = deadline - System.currentTimeMillis()) > 0) {
      T next = queue.poll(remaining, TimeUnit.MILLISECONDS);
      if (next != null && match.test(next)) {
        return next;
      }
    }
    return null;
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
      Thread.sleep(20);
    }
  }
}
