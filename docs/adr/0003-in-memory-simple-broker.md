# 0003. Start with Spring's in-memory simple broker

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

STOMP destinations need a broker to track subscriptions and fan messages out. Spring offers an
in-process **simple broker**, or a **broker relay** to RabbitMQ or ActiveMQ over TCP. ApexChat
currently runs as a single instance.

## Decision

Use the **simple broker** (`enableSimpleBroker("/topic", "/queue")`) with server heartbeats driven by
a dedicated `ThreadPoolTaskScheduler`. Transport limits are set explicitly:

| Limit | Value |
|---|---|
| Message size | 64 KB |
| Send buffer | 512 KB |
| Send time | 15 s |

A slow consumer therefore cannot exhaust server memory.

## Alternatives considered

- **RabbitMQ STOMP relay now.** This would enable horizontal scaling immediately. It also adds an
  infrastructure dependency to every developer machine, test run and deployment before we need it.

## Consequences

- There are no extra moving parts. `java -jar` gives a complete, working system.
- There is **one instance only**. Subscriptions, presence ([ADR-0006](0006-session-counted-presence.md))
  and the rate limiter live in JVM memory.
- **Scaling path:** switch to `enableStompBrokerRelay(...)`, then move presence and rate-limit
  state to a shared store such as Redis. Message persistence is already in the database, so
  history needs no change. Controllers and clients are unaffected because destinations stay the
  same.
