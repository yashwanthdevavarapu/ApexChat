# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-11-12

### Added

- Real-time channel messaging over STOMP/WebSocket, with persistence before broadcast and
  keyset-paginated history.
- Direct messages delivered to every session of both participants.
- Session-counted presence (multi-tab aware) with an instant snapshot on subscribe.
- Typing indicators for channels and DMs.
- Channel creation broadcast live to all users.
- Form login and registration (BCrypt, case-insensitive usernames).
- CSRF-checked STOMP CONNECT, a destination allow-list, strict CSP, and per-user rate limiting.
- Responsive UI with dark and light themes, unread badges, deep links and safe auto-linking.
- Flyway schema portable across PostgreSQL 16 and H2, and a `postgres` profile.
- Prometheus metrics for message throughput, online users and sessions.
- Tests: 56 unit, 32 integration (including Testcontainers PostgreSQL), and 26 Playwright
  end-to-end. 80% coverage gate.
- Latency benchmark comparing STOMP push with HTTP request/response and polling.
- Docker image, `docker compose` stack, and GitHub Actions for CI, CodeQL, dependency review and
  releases.
- Ten Architecture Decision Records.

### Fixed

- Direct messages to a user with several open tabs failed when the STOMP ordering flags were
  enabled. Ordering now relies on message ids ([ADR-0007](docs/adr/0007-message-ordering.md)).
- Hardening from code review:
  - Passwords are validated in bytes, matching BCrypt's 72-byte limit, so long non-ASCII
    passwords no longer return a 500.
  - Concurrent duplicate sign-ups or room names are mapped to 409 instead of a 500.
  - Presence frames are versioned so stale frames are ignored, and a disconnect processed before
    its connect no longer leaves a ghost online user.
  - Typing hints have their own rate limit.
  - The message-length setting is capped at the database column size.
  - The rate limiter now sweeps idle users.
  - Rejected messages are restored to the composer, and the typing state resets after a reconnect.

[Unreleased]: https://github.com/yashwanthdevavarapu/apexchat/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/yashwanthdevavarapu/apexchat/releases/tag/v1.0.0
