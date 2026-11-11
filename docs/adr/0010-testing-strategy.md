# 0010. Three-layer testing strategy with a coverage gate

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Real-time systems fail in ways unit tests miss: handshake authentication, frame authorization,
user-destination routing, multi-session presence and browser rendering. We still want fast feedback.

## Decision

| Layer | Tooling | Runs in | What it proves |
|---|---|---|---|
| Unit (`*Test`) | JUnit 5, Mockito, AssertJ | `mvn test` (~15 s) | Validation, rate limiting, slugs, presence state machine (including a 16-thread race test) |
| Integration (`*IT`) | `@SpringBootTest`, real STOMP client over real WebSocket, MockMvc, Testcontainers | `mvn verify` | Login → CSRF → CONNECT; fan-out; DM privacy; multi-session delivery; ERROR frames; REST pagination and problem details; security headers; PostgreSQL migrations |
| End-to-end | Playwright (Chromium), multiple isolated browser contexts | `cd e2e && npm test`, CI job `e2e` | Two or three real users chatting: live delivery, typing, presence, unread badges, XSS safety, deep links, sign-out |

- JaCoCo merges unit and integration coverage. `mvn verify` **fails below 80% line coverage**.
  The current figure is about 96% lines and 88% branches.
- Integration tests never `sleep` to wait for subscriptions. They publish **probe frames** until a
  subscription delivers one, then assert on real content.
- A separate, opt-in **latency benchmark** (`mvn test -Pbenchmark`) compares STOMP push with HTTP
  request/response and polling. It is excluded from normal runs so they stay fast.
- The same Playwright suite also generates the README screenshots (`npm run screenshots`), so
  documentation images come from the real app.

## Consequences

- CI catches protocol and security regressions that mocks cannot. For example, the multi-session
  ordering bug in [ADR-0007](0007-message-ordering.md) was found by an integration test.
- End-to-end tests are the slowest layer (about 2–3 minutes). They run in a separate CI job, in
  parallel with the Docker build.
