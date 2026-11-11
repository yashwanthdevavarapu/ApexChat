# 0007. Database id is the ordering authority

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Spring's STOMP pipeline processes frames on thread pools. Without extra configuration, two frames
from one client can be handled concurrently, and two broadcasts to one client can be written out of
order. Spring 6.1+ offers two flags to prevent this:

- `StompEndpointRegistry.setPreserveReceiveOrder(true)`
- `MessageBrokerRegistry.setPreservePublishOrder(true)`

We enabled both at first. The integration test
`StompMessagingIT.directMessagesFanOutToEverySessionOfTheRecipient` then failed. With either flag
on, `UserDestinationMessageHandler` sends through per-session ordered templates. When one user
destination fans out to **several sessions of the same user**, it throws:

```
IllegalArgumentException: Expected mutable SimpMessageHeaderAccessor
  at OrderedMessageChannelDecorator.setTaskHeader(...)
  at UserDestinationMessageHandler$SendHelper.send(...)
```

(Spring Framework 6.2.19.) In product terms: a direct message to anyone with two tabs open would
silently fail to reach them.

## Decision

- Do **not** enable `preserveReceiveOrder` or `preservePublishOrder`. A comment in
  `WebSocketConfig` points here.
- The **database identity column** is the canonical order. Ids are assigned at insert and returned
  in every `MessageDto`.
- The **client orders by id**. Appends are the fast path. If a frame arrives with an id lower than
  the last rendered one, the client re-sorts and re-renders. History pages are ordered by id, and
  live frames are de-duplicated against them by id.

## Alternatives considered

- **Keep the flags and avoid `convertAndSendToUser` for direct messages**, by resolving sessions
  manually. This re-implements Spring's user-destination routing and is fragile.
- **Keep the flags and give up multi-session delivery.** This breaks a core feature.
- **Order by `sentAt`.** Timestamps can collide at millisecond resolution and depend on the clock.
  The id is unique and monotonic.

## Consequences

- Multi-tab and multi-device direct messages work. A regression test pins this.
- If one person sends two messages within the same few milliseconds, the server may persist them
  in either order. For human typing speeds this is not observable. Every client still shows the
  same order, because every client sorts by the same ids.
- **Revisit** when upgrading Spring. If the framework fixes multi-session ordered sends, enabling
  `preserveReceiveOrder` would tighten per-sender ordering at no cost. The fan-out test will
  verify it.
