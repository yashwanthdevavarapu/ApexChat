# 0006. Presence by counting live STOMP sessions per user

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

"Online" must be accurate for users with several tabs or devices. Closing one tab must not mark
someone offline. Spring publishes `SessionConnectedEvent` and `SessionDisconnectEvent`, but:

- Disconnect events can arrive more than once for the same session.
- Connects and disconnects for one user can race on different threads.

## Decision

- `PresenceService` keeps a `ConcurrentHashMap<username, Set<sessionId>>`. Every mutation goes
  through `compute`, which is atomic per key, so connects and disconnects for the same user are
  serialized without a global lock.
- `connect` returns `true` only for the user's **first** session, and `disconnect` returns `true`
  only when the **last** session closes. Only those transitions are broadcast on `/topic/presence`.
  Removing an unknown session is a no-op, so duplicate disconnect events are harmless.
- Each event carries the **full roster**, not only a delta, so a client that missed an event heals
  on the next one.
- A client subscribes to `/topic/presence` for changes and to `/app/presence` (a
  `@SubscribeMapping`) for an immediate snapshot. This closes the gap between connecting and
  receiving the first broadcast.
- Heartbeats (10 s) make sure dead connections, such as a laptop lid closed or a network drop, are
  detected and produce a disconnect event.
- Micrometer gauges expose `apexchat.presence.online.users` and `apexchat.presence.sessions`.

## Alternatives considered

- **A boolean per user.** This is wrong with multiple tabs: the first tab to close marks the user
  offline.
- **Client "I'm here" pings over HTTP.** This duplicates what STOMP heartbeats already do, and
  presence becomes stale for up to one ping interval.

## Consequences

- A concurrency test hammers connect and disconnect from 16 threads and asserts that every ONLINE
  has exactly one matching OFFLINE. An integration test and a Playwright test cover the
  "two tabs, close one" case.
- State is per-instance. Scaling out needs a shared store ([ADR-0003](0003-in-memory-simple-broker.md)).
