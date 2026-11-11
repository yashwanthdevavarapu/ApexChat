# 0005. Session-cookie auth + CSRF-checked STOMP CONNECT

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Browsers attach cookies to WebSocket handshakes from **any** origin. If the handshake were trusted
on the strength of the session cookie alone, a malicious page could open a socket as the victim.
This attack is called Cross-Site WebSocket Hijacking. Every STOMP frame also needs per-destination
authorization, so that, for example, nobody can subscribe to another user's resolved queue.

## Decision

- **HTTP:** Spring Security form login with BCrypt-hashed passwords. Usernames are case-insensitive
  and stored lower-case. `/api/**` returns `401` rather than redirecting to the login page.
- **Handshake:** `/ws` requires an authenticated HTTP session. The STOMP principal is the session's
  user. The WebSocket origin check uses Spring's default, which allows same-origin only. Extra
  origins are opt-in via `apexchat.allowed-origins`.
- **CONNECT:** `@EnableWebSocketSecurity` requires the session's CSRF token in the CONNECT frame's
  `X-CSRF-TOKEN` header. The Thymeleaf shell embeds the token, so a cross-origin page cannot read it.
- **Frames:** `WebSocketSecurityConfig` contains an allow-list.
  - SEND is allowed only to `/app/**`.
  - SUBSCRIBE is allowed only to `/topic/rooms`, `/topic/rooms/*`, `/topic/rooms/*/typing`,
    `/topic/presence` and `/user/queue/**`.
  - Everything else is denied, including MESSAGE frames sent directly to the broker. A denied frame
    produces a STOMP ERROR frame and closes the session. The ERROR frame message is generic and
    does not leak the reason.
- **Browser hardening:**
  - A strict CSP (`default-src 'self'`, no inline script, `frame-ancestors 'none'`).
  - `SameSite=Lax`, HttpOnly session cookies.
  - Message text is always rendered with `textContent`, never `innerHTML`.

## Alternatives considered

- **JWT in a CONNECT header.** This would suit a separate SPA or mobile clients. For a
  server-rendered app it adds token storage in JavaScript, which an XSS could exfiltrate, and
  refresh logic, without adding security.
- **Origin check only.** This is necessary but not sufficient. Non-browser clients can forge
  `Origin`, and the CSRF token adds defence in depth.

## Consequences

- These behaviours are pinned by tests:
  - CONNECT without CSRF fails.
  - An anonymous handshake fails.
  - Subscribing outside the allow-list closes the session.
  - An XSS payload renders as text (`StompMessagingIT`, `security.spec.ts`, `messaging.spec.ts`).
- A future native mobile client would need a token-based CONNECT path. That would be a new ADR.
