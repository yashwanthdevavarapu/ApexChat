# Architecture

ApexChat is a single Spring Boot service. It serves a server-rendered shell, then switches to one
long-lived **STOMP-over-WebSocket** connection per browser tab. Everything after first paint
(messages, typing hints, presence, new channels) flows over that socket. History comes from a
small REST API.

## Component view

```mermaid
flowchart LR
  subgraph Browser
    UI["chat.js<br/>(ES module, no framework)"]
    STOMPJS["@stomp/stompjs<br/>reconnect + heartbeats"]
    UI --- STOMPJS
  end

  subgraph "Spring Boot (single JVM)"
    SEC["Spring Security<br/>form login · CSRF · CSP"]
    WS["/ws endpoint<br/>WebSocket handshake"]
    AUTHZ["Message authorization<br/>(destination allow-list)"]
    CTRL["@MessageMapping<br/>ChatMessagingController"]
    REST["REST controllers<br/>rooms · history · presence"]
    SVC["MessageService · RoomService<br/>RateLimiter"]
    PRES["PresenceService<br/>+ session event listener"]
    BROKER[("Simple broker<br/>/topic · /queue")]
    DB[("PostgreSQL / H2<br/>Flyway schema")]
  end

  STOMPJS -- "WebSocket (STOMP frames)" --> WS
  UI -- "HTTP (history, create room)" --> SEC --> REST
  WS --> AUTHZ --> CTRL --> SVC
  REST --> SVC
  SVC -- "1 · save" --> DB
  SVC -- "2 · publish" --> BROKER
  WS -. "connect / disconnect events" .-> PRES --> BROKER
  BROKER -- "MESSAGE frames" --> STOMPJS
```

## Sending a channel message

```mermaid
sequenceDiagram
  autonumber
  participant A as Alice (browser)
  participant S as Spring STOMP pipeline
  participant M as MessageService
  participant D as Database
  participant B as Simple broker
  participant O as Bob (browser)

  A->>S: SEND /app/rooms/general/send {"content":"hi"}
  S->>S: authorize frame (principal = alice)
  S->>M: sendToRoom("alice","general","hi")
  M->>M: validate · room exists · rate limit
  M->>D: INSERT chat_message → id 812
  M->>B: publish /topic/rooms/general {id:812,…}
  B-->>A: MESSAGE (sender echo)
  B-->>O: MESSAGE
  Note over A,O: Both clients render by id (ADR-0007)
```

Errors such as a blank message, an unknown room, or sending too fast are thrown as `ChatException`
subclasses. `@MessageExceptionHandler` sends them **only to the sender's session** on
`/user/queue/errors`, and the UI shows them as a toast.

## Presence across tabs

```mermaid
stateDiagram-v2
  [*] --> Offline
  Offline --> Online: first session connects<br/>(broadcast ONLINE)
  Online --> Online: another tab connects / one of several closes<br/>(no broadcast)
  Online --> Offline: last session closes or heartbeat expires<br/>(broadcast OFFLINE)
```

## Destinations

| Direction | Destination | Purpose |
|---|---|---|
| client → server | `/app/rooms/{slug}/send` | Post to a channel |
| client → server | `/app/rooms/{slug}/typing` | Typing hint in a channel |
| client → server | `/app/dm/{user}/send` | Send a direct message |
| client → server | `/app/dm/{user}/typing` | Typing hint in a DM |
| subscribe (reply) | `/app/presence` | One-off roster snapshot |
| server → client | `/topic/rooms` | A channel was created |
| server → client | `/topic/rooms/{slug}` | Channel messages |
| server → client | `/topic/rooms/{slug}/typing` | Channel typing hints |
| server → client | `/topic/presence` | ONLINE / OFFLINE + full roster |
| server → client | `/user/queue/dm` | DMs to *and from* me (all my sessions) |
| server → client | `/user/queue/typing` | DM typing hints |
| server → client | `/user/queue/errors` | Errors for frames I sent |

## Package layout

```
com.apexchat
├── config/     WebSocket + broker, HTTP security, STOMP authorization, properties, clock
├── common/     ChatException hierarchy, ErrorEvent, RateLimiter
├── user/       AppUser, registration, UserDetailsService, login/register pages
├── room/       ChatRoom, slugs, create/list, REST
├── message/    ChatMessage, MessageService, STOMP + REST controllers, DTOs
├── presence/   PresenceService, session event listener, snapshot endpoints
└── web/        Chat page, RFC 9457 problem responses
```

## Observability

- `GET /actuator/health/{liveness,readiness}`: public, used by the Docker `HEALTHCHECK` and CI
  smoke test.
- `GET /actuator/prometheus`: requires authentication. Custom meters:
  - `apexchat_messages_sent_total{channel="room|direct"}`
  - `apexchat_presence_online_users`
  - `apexchat_presence_sessions`
  - plus Spring's built-in WebSocket session stats.

Decision records for everything above live in [`docs/adr/`](adr/README.md).
