# Architecture Decision Records

Significant design decisions are recorded here using Michael Nygard's lightweight
[ADR format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
**Context → Decision → Consequences**, plus the alternatives that were rejected.

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-stomp-over-native-websocket.md) | STOMP over native WebSocket (no SockJS) | Accepted |
| [0003](0003-in-memory-simple-broker.md) | Start with Spring's in-memory simple broker | Accepted |
| [0004](0004-persist-then-broadcast.md) | Persist first, then broadcast; history over REST | Accepted |
| [0005](0005-websocket-authentication-and-csrf.md) | Session-cookie auth + CSRF-checked STOMP CONNECT | Accepted |
| [0006](0006-session-counted-presence.md) | Presence by counting live STOMP sessions per user | Accepted |
| [0007](0007-message-ordering.md) | Database id is the ordering authority | Accepted |
| [0008](0008-thymeleaf-shell-vanilla-js-client.md) | Thymeleaf shell + framework-free JS client | Accepted |
| [0009](0009-portable-schema-flyway-h2-postgres.md) | One Flyway schema for H2 (dev) and PostgreSQL (prod) | Accepted |
| [0010](0010-testing-strategy.md) | Three-layer testing strategy with a coverage gate | Accepted |

## Adding a new ADR

1. Copy [`template.md`](template.md) to `NNNN-short-title.md` using the next number.
2. Fill it in and open a pull request; discussion happens on the PR.
3. ADRs are immutable once accepted. To change a decision, write a new ADR that
   **supersedes** the old one and update the old one's status.
