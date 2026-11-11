# 0002. STOMP over native WebSocket (no SockJS)

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Chat needs server push with low latency. Classic request/response forces clients to poll, which
trades latency against wasted requests. The [latency benchmark](../benchmark.md) quantifies this.
WebSocket gives a persistent, full-duplex connection, but raw WebSocket is only a byte pipe. We
would have to invent framing, destinations, subscriptions, per-user routing, heartbeats and error
reporting ourselves.

## Decision

- Use **STOMP 1.2 over WebSocket** via Spring's `@EnableWebSocketMessageBroker`:
  - `/app/**` frames are routed to `@MessageMapping` methods.
  - `/topic/**` is used for fan-out.
  - `/user/queue/**` is used for per-user delivery, which Spring resolves to every session of
    that user.
- Expose a single endpoint, `/ws`, using **native WebSocket only**. **SockJS** is not used.
- The browser uses `@stomp/stompjs` 7, served as a WebJar. It handles reconnects (every 2 s) and
  heartbeats (10 s both ways, matching the server).

## Alternatives considered

- **Raw WebSocket + a custom JSON protocol.** This means less library code, but we would
  re-implement subscriptions, user routing, heartbeats and error frames, with no tooling support.
- **Server-Sent Events + HTTP POST.** This is simple and proxy-friendly, but one-directional.
  Typing indicators and sends would cost a full HTTP request each, and per-user routing would still
  be custom.
- **SockJS fallback.** It was needed for IE-era browsers and hostile proxies. Every browser we
  target supports WebSocket. SockJS adds a second transport to secure and test, and its streaming
  and polling fallbacks complicate CSRF and CSP.

## Consequences

- Spring Security can authorize individual destinations ([ADR-0005](0005-websocket-authentication-and-csrf.md)).
- Moving to an external broker later is a configuration change, because STOMP is broker-neutral
  ([ADR-0003](0003-in-memory-simple-broker.md)).
- Networks that block WebSocket upgrades cannot connect. The UI shows a "Reconnecting" state
  rather than degrading silently.
