# API reference

Everything except health probes requires an authenticated session (form login at `/login`).
State-changing HTTP requests need the CSRF token in the `X-CSRF-TOKEN` header. The chat page
exposes it as `#app[data-csrf-token]`.

## REST

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/rooms` | List channels (oldest first) |
| `POST` | `/api/rooms` | Create a channel `{ "name": "Release Train", "description": "…" }` → `201` |
| `GET` | `/api/rooms/{slug}/messages?before={id}&limit={n}` | Channel history, oldest-first page ending before `before` |
| `POST` | `/api/rooms/{slug}/messages` | Post `{ "content": "…" }` over HTTP (also broadcast live) → `201` |
| `GET` | `/api/dm/{username}/messages?before={id}&limit={n}` | Direct-message history with `username` |
| `GET` | `/api/presence` | `{ "type": "SNAPSHOT", "online": ["alice", …] }` |
| `GET` | `/actuator/health/readiness` | Public readiness probe |

`limit` defaults to 50 and is capped at 100.

### Errors

Failures use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details with a stable
`code`:

```json
{ "status": 404, "title": "Not Found", "detail": "Room 'nope' does not exist", "code": "ROOM_NOT_FOUND" }
```

| HTTP | `code` |
|---|---|
| 400 | `EMPTY_MESSAGE`, `MESSAGE_TOO_LONG`, `SELF_MESSAGE`, `INVALID_ROOM_NAME`, `VALIDATION_FAILED` |
| 401 | unauthenticated (no body) |
| 403 | missing or invalid CSRF token |
| 404 | `ROOM_NOT_FOUND`, `USER_NOT_FOUND` |
| 409 | `ROOM_EXISTS` |
| 429 | `RATE_LIMITED` (default: 20 messages per 10 s per user, across all sessions) |

## STOMP

Connect to `ws(s)://<host>/ws` with the session cookie, and send CONNECT with the CSRF header:

```js
const client = new StompJs.Client({
  brokerURL: `wss://${location.host}/ws`,
  connectHeaders: { "X-CSRF-TOKEN": csrfToken },
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
});
client.onConnect = () => {
  client.subscribe("/topic/rooms/general", (f) => console.log(JSON.parse(f.body)));
  client.publish({ destination: "/app/rooms/general/send", body: JSON.stringify({ content: "hi" }) });
};
client.activate();
```

See the [destination table](architecture.md#destinations) for every destination.

### Payloads

```jsonc
// MessageDto: on /topic/rooms/{slug} and /user/queue/dm
{ "id": 812, "room": "general", "sender": "alice", "recipient": null,
  "content": "hi", "sentAt": "2026-11-12T09:30:00.123Z" }

// TypingEvent: on /topic/rooms/{slug}/typing and /user/queue/typing
{ "username": "alice", "room": "general", "typing": true }

// PresenceEvent: on /topic/presence and /app/presence
{ "type": "ONLINE", "username": "bob", "online": ["alice", "bob"], "at": "…" }

// ErrorEvent: on /user/queue/errors
{ "code": "RATE_LIMITED", "message": "You are sending messages too quickly. Please slow down." }
```
