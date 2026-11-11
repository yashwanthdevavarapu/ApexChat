package com.apexchat.support;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Drives the server exactly like a browser does: form login over HTTP, read the CSRF token from the
 * chat page, then open a STOMP session with the session cookie and CSRF header.
 */
public final class AuthenticatedStompClient {

  private static final Pattern FORM_CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final Pattern PAGE_CSRF = Pattern.compile("data-csrf-token=\"([^\"]+)\"");
  private static final Pattern PAGE_HEADER = Pattern.compile("data-csrf-header=\"([^\"]+)\"");

  private final int port;
  private final CookieManager cookies = new CookieManager();
  private final BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
  private final HttpClient http;
  private String csrfHeader;
  private String csrfToken;

  public AuthenticatedStompClient(int port) {
    this.port = port;
    this.http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
  }

  /** Logs in over HTTP and captures the CSRF token bound to the new session. */
  public AuthenticatedStompClient login(String username, String password) throws Exception {
    String loginPage = get("/login").body();
    String form =
        form(
            Map.of(
                "username", username, "password", password, "_csrf", find(FORM_CSRF, loginPage)));
    HttpResponse<String> res =
        http.send(
            HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String location = res.headers().firstValue("Location").orElse("");
    if (res.statusCode() != 302 || location.contains("error")) {
      throw new IllegalStateException("Login failed for " + username + ": " + location);
    }
    String chat = get("/chat").body();
    csrfHeader = find(PAGE_HEADER, chat);
    csrfToken = find(PAGE_CSRF, chat);
    return this;
  }

  public StompSession connect() throws Exception {
    return connect(true);
  }

  /** Opens a STOMP session; pass {@code false} to omit the CSRF header from CONNECT. */
  public StompSession connect(boolean withCsrf) throws Exception {
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    String cookieHeader =
        cookies.getCookieStore().getCookies().stream()
            .map(c -> c.getName() + "=" + c.getValue())
            .collect(Collectors.joining("; "));
    if (!cookieHeader.isEmpty()) {
      handshake.add("Cookie", cookieHeader);
    }
    StompHeaders connect = new StompHeaders();
    if (withCsrf && csrfHeader != null) {
      connect.add(csrfHeader, csrfToken);
    }
    StompSessionHandlerAdapter handler =
        new StompSessionHandlerAdapter() {
          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(headers); // only ERROR frames reach the session-level handler
          }
        };
    return stompClient()
        .connectAsync("ws://localhost:" + port + "/ws", handshake, connect, handler)
        .get(5, TimeUnit.SECONDS);
  }

  /** STOMP ERROR frames the server sent on sessions opened by this client. */
  public BlockingQueue<StompHeaders> errors() {
    return errors;
  }

  public HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  /** POSTs JSON with the session cookie and CSRF header, like the browser's fetch() does. */
  public HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .header(csrfHeader, csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  public static WebSocketStompClient stompClient() {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    converter.setObjectMapper(JsonMapper.builder().findAndAddModules().build());
    client.setMessageConverter(converter);
    return client;
  }

  /** Subscribes and collects every payload into a queue the test can poll. */
  public static <T> BlockingQueue<T> subscribe(
      StompSession session, String destination, Class<T> type) {
    BlockingQueue<T> queue = new LinkedBlockingQueue<>();
    session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return type;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            queue.add(type.cast(payload));
          }
        });
    return queue;
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String find(Pattern pattern, String html) {
    Matcher m = pattern.matcher(html);
    if (!m.find()) {
      throw new IllegalStateException("Pattern " + pattern + " not found in page");
    }
    return m.group(1).replace("&#45;", "-");
  }

  private static String form(Map<String, String> fields) {
    return List.copyOf(fields.entrySet()).stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
