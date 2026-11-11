package com.apexchat.benchmark;

import static com.apexchat.support.AuthenticatedStompClient.subscribe;
import static org.assertj.core.api.Assertions.assertThat;

import com.apexchat.message.MessageDto;
import com.apexchat.message.SendMessageRequest;
import com.apexchat.support.AuthenticatedStompClient;
import com.apexchat.user.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompSession;

/**
 * Measures end-to-end delivery latency (sender submits -> another user has the message) for:
 *
 * <ol>
 *   <li><b>STOMP push</b> over an already-open WebSocket
 *   <li><b>HTTP best case</b>: POST, then the receiver immediately GETs (zero polling delay — the
 *       theoretical floor for any request/response design)
 *   <li><b>HTTP short polling</b> every 1s, the usual request/response chat design
 * </ol>
 *
 * Run with {@code mvn test -Pbenchmark}. Results go to stdout and target/benchmark/latency.md.
 */
@Tag("benchmark")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"apexchat.rate-limit.max-messages=1000000", "logging.level.root=WARN"})
class LatencyBenchmarkTest {

  private static final int WARMUP = 200;
  private static final int SAMPLES = 1000;
  private static final int POLL_SAMPLES = 60;
  private static final long POLL_INTERVAL_MS = 1000;
  private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

  @LocalServerPort int port;
  @Autowired UserService users;

  @Test
  void compareDeliveryLatency() throws Exception {
    users.register("bench_sender", "bench-password");
    users.register("bench_receiver", "bench-password");
    var sender = new AuthenticatedStompClient(port).login("bench_sender", "bench-password");
    var receiver = new AuthenticatedStompClient(port).login("bench_receiver", "bench-password");

    double[] ws = measureWebSocket(sender, receiver);
    double[] httpFloor = measureHttpBestCase(sender, receiver);
    double[] polling = measureShortPolling(sender, receiver);

    String report =
        String.format(
            Locale.ROOT,
            """
            ## Delivery latency (sender submits -> receiver has the message)

            JVM %s, %s cores, loopback network, %d samples (polling: %d).

            | Transport | p50 (ms) | p95 (ms) | mean (ms) |
            |---|---:|---:|---:|
            | STOMP over WebSocket (push) | %.2f | %.2f | %.2f |
            | HTTP request/response, best case (POST + immediate GET) | %.2f | %.2f | %.2f |
            | HTTP short polling every %d ms | %.1f | %.1f | %.1f |

            WebSocket p50 is **%.0f%% lower** than the HTTP best case and **%.1f%% lower** than 1s polling.
            """,
            System.getProperty("java.version"),
            Runtime.getRuntime().availableProcessors(),
            SAMPLES,
            POLL_SAMPLES,
            pct(ws, 50),
            pct(ws, 95),
            mean(ws),
            pct(httpFloor, 50),
            pct(httpFloor, 95),
            mean(httpFloor),
            POLL_INTERVAL_MS,
            pct(polling, 50),
            pct(polling, 95),
            mean(polling),
            100 * (1 - pct(ws, 50) / pct(httpFloor, 50)),
            100 * (1 - pct(ws, 50) / pct(polling, 50)));
    System.out.println(report);
    Path out = Path.of("target", "benchmark", "latency.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report);

    assertThat(pct(ws, 50)).isLessThan(pct(polling, 50));
  }

  private double[] measureWebSocket(AuthenticatedStompClient s, AuthenticatedStompClient r)
      throws Exception {
    StompSession tx = s.connect();
    StompSession rx = r.connect();
    var inbox = subscribe(rx, "/topic/rooms/engineering", MessageDto.class);
    // SUBSCRIBE is processed asynchronously: send until the first message comes through
    while (inbox.poll(200, TimeUnit.MILLISECONDS) == null) {
      tx.send("/app/rooms/engineering/send", new SendMessageRequest("ready?"));
    }
    Thread.sleep(300);
    inbox.clear();
    double[] samples = new double[SAMPLES];
    for (int i = -WARMUP; i < SAMPLES; i++) {
      long start = System.nanoTime();
      tx.send("/app/rooms/engineering/send", new SendMessageRequest("ws " + i));
      MessageDto got = inbox.poll(5, TimeUnit.SECONDS);
      long end = System.nanoTime();
      assertThat(got).isNotNull();
      if (i >= 0) {
        samples[i] = (end - start) / 1e6;
      }
    }
    tx.disconnect();
    rx.disconnect();
    return samples;
  }

  private double[] measureHttpBestCase(AuthenticatedStompClient s, AuthenticatedStompClient r)
      throws Exception {
    double[] samples = new double[SAMPLES];
    for (int i = -WARMUP; i < SAMPLES; i++) {
      long start = System.nanoTime();
      long id =
          idOf(s.post("/api/rooms/random/messages", "{\"content\":\"http " + i + "\"}").body());
      String page = r.get("/api/rooms/random/messages?limit=1").body();
      long end = System.nanoTime();
      assertThat(idOf(page)).isEqualTo(id);
      if (i >= 0) {
        samples[i] = (end - start) / 1e6;
      }
    }
    return samples;
  }

  private double[] measureShortPolling(AuthenticatedStompClient s, AuthenticatedStompClient r)
      throws Exception {
    Map<Long, Long> firstSeen = new ConcurrentHashMap<>();
    Thread poller =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  String page = r.get("/api/rooms/general/messages?limit=20").body();
                  long now = System.nanoTime();
                  Matcher m = ID.matcher(page);
                  while (m.find()) {
                    firstSeen.putIfAbsent(Long.parseLong(m.group(1)), now);
                  }
                  Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                  return;
                } catch (Exception ignored) {
                  // keep polling
                }
              }
            },
            "bench-poller");
    poller.setDaemon(true);
    poller.start();
    Thread.sleep(POLL_INTERVAL_MS); // let the poller absorb existing history
    List<long[]> sent = new ArrayList<>();
    for (int i = 0; i < POLL_SAMPLES; i++) {
      Thread.sleep(ThreadLocalRandom.current().nextLong(200, 900));
      long start = System.nanoTime();
      long id =
          idOf(s.post("/api/rooms/general/messages", "{\"content\":\"poll " + i + "\"}").body());
      sent.add(new long[] {id, start});
    }
    Thread.sleep(POLL_INTERVAL_MS * 2);
    poller.interrupt();
    return sent.stream()
        .mapToDouble(p -> (firstSeen.getOrDefault(p[0], Long.MAX_VALUE) - p[1]) / 1e6)
        .toArray();
  }

  private static long idOf(String json) {
    Matcher m = ID.matcher(json);
    assertThat(m.find()).as("id in %s", json).isTrue();
    return Long.parseLong(m.group(1));
  }

  private static double pct(double[] values, int percentile) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int idx = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, idx)];
  }

  private static double mean(double[] values) {
    return Arrays.stream(values).average().orElse(Double.NaN);
  }
}
