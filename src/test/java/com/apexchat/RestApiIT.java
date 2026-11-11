package com.apexchat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class RestApiIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  /** A fresh principal per test so the per-user rate limit never interferes. */
  private static RequestPostProcessor someone() {
    return user("rest_" + SEQ.incrementAndGet());
  }

  private String createRoom(RequestPostProcessor who, String name) throws Exception {
    String body =
        mvc.perform(
                post("/api/rooms")
                    .with(who)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"description\":\"for tests\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("slug").asText();
  }

  private long postMessage(RequestPostProcessor who, String slug, String content) throws Exception {
    String body =
        mvc.perform(
                post("/api/rooms/{slug}/messages", slug)
                    .with(who)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(java.util.Map.of("content", content))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  void anonymousApiCallsGet401NotARedirect() throws Exception {
    mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
  }

  @Test
  void anonymousPageVisitsRedirectToLogin() throws Exception {
    mvc.perform(get("/chat").accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("**/login"));
  }

  @Test
  void listsSeededRooms() throws Exception {
    mvc.perform(get("/api/rooms").with(someone()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].slug", hasItem("general")))
        .andExpect(jsonPath("$[*].slug", hasItem("engineering")))
        .andExpect(jsonPath("$[*].slug", hasItem("random")));
  }

  @Test
  void createsRoomsAndRejectsDuplicatesAndBadInput() throws Exception {
    var who = someone();
    String slug = createRoom(who, "Release Train " + SEQ.incrementAndGet());
    mvc.perform(get("/api/rooms").with(who)).andExpect(jsonPath("$[*].slug", hasItem(slug)));

    mvc.perform(
            post("/api/rooms")
                .with(who)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + slug.toUpperCase() + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_EXISTS"));

    mvc.perform(
            post("/api/rooms")
                .with(who)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void stateChangingRequestsRequireCsrf() throws Exception {
    mvc.perform(
            post("/api/rooms")
                .with(someone())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no-csrf\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void historyIsKeysetPaginatedOldestFirst() throws Exception {
    var who = someone();
    String slug = createRoom(who, "Paging " + SEQ.incrementAndGet());
    long[] ids = new long[5];
    for (int i = 0; i < 5; i++) {
      ids[i] = postMessage(who, slug, "m" + i);
    }

    String latest =
        mvc.perform(get("/api/rooms/{slug}/messages", slug).param("limit", "2").with(who))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].content").value("m3"))
            .andExpect(jsonPath("$[1].content").value("m4"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode page = json.readTree(latest);

    mvc.perform(
            get("/api/rooms/{slug}/messages", slug)
                .param("limit", "2")
                .param("before", page.get(0).get("id").asText())
                .with(who))
        .andExpect(jsonPath("$[0].content").value("m1"))
        .andExpect(jsonPath("$[1].content").value("m2"))
        .andExpect(jsonPath("$[1].id").value(ids[2]));
  }

  @Test
  void rejectsBlankMessagesAndUnknownRooms() throws Exception {
    var who = someone();
    mvc.perform(
            post("/api/rooms/general/messages")
                .with(who)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EMPTY_MESSAGE"));

    mvc.perform(get("/api/rooms/nope-nope/messages").with(who))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("Room 'nope-nope' does not exist"));

    mvc.perform(get("/api/dm/ghost_user/messages").with(who))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void restFloodingIsRateLimited() throws Exception {
    var who = someone();
    for (int i = 0; i < 20; i++) {
      postMessage(who, "random", "burst " + i);
    }
    mvc.perform(
            post("/api/rooms/random/messages")
                .with(who)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"one too many\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void presenceSnapshotIsAvailableOverRest() throws Exception {
    mvc.perform(get("/api/presence").with(someone()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SNAPSHOT"))
        .andExpect(jsonPath("$.online").isArray());
  }

  @Test
  void healthIsPublicButMetricsRequireAuthentication() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

    postMessage(someone(), "general", "count me");
    mvc.perform(get("/actuator/prometheus").with(someone()))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("apexchat_messages_sent_total{channel=\"room\"")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("apexchat_presence_online_users")));
  }

  @Test
  void responsesCarrySecurityHeaders() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "same-origin"));
  }
}
