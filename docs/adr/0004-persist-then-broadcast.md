# 0004. Persist first, then broadcast; history over REST

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

A user who reloads, reconnects or opens a new tab must see messages they missed. Live frames and
stored history therefore have to agree on identity and content.

## Decision

1. `MessageService` validates the message, saves it (which assigns its id), and only then
   broadcasts the saved message. Nothing is broadcast that is not durable.
2. Sending is deliberately *not* wrapped in a transaction that spans the broadcast. The repository
   save commits on its own, so a client that receives a frame and immediately queries history will
   find the message.
3. History is served over REST with **keyset pagination**: `GET …/messages?before={id}&limit=50`.
   This is backed by the indexes `(room_id, id)` and `(sender, recipient, id)`. Pages are returned
   oldest-first for direct rendering.
4. The client loads history over REST, merges live frames by message `id`, and re-fetches the
   active conversation after every reconnect.

## Alternatives considered

- **Broadcast first, persist asynchronously.** Latency is slightly lower, but a crash between the
  two steps shows users messages that later vanish.
- **History over STOMP (`@SubscribeMapping`).** This works, but REST is cacheable, easy to test and
  easy to paginate.
- **Offset pagination.** Pages become unstable while new messages arrive, and deep offsets get
  slow.

## Consequences

- The database is on the hot path of every send. The measured end-to-end p50 is about 9.5 ms on
  loopback.
- Reconnect recovery is simple and correct: re-fetch, then de-duplicate by id.
