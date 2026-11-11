# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 1.x | ✅ |

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through
[GitHub Security Advisories](https://github.com/yashwanthdevavarapu/apexchat/security/advisories/new).
Include steps to reproduce and the impact you observed.

You can expect an acknowledgement within 3 working days and a fix or mitigation plan within 14 days
for confirmed issues. We will credit you in the release notes unless you prefer otherwise.

## Security model (summary)

- Form login with BCrypt password hashes, and HttpOnly `SameSite=Lax` session cookies.
- The WebSocket handshake requires an authenticated session. STOMP `CONNECT` must carry the
  session's CSRF token, which prevents cross-site WebSocket hijacking.
- Every inbound STOMP frame is checked against a destination allow-list. A denied frame closes the
  session.
- Strict Content-Security-Policy (`default-src 'self'`, no inline script, `frame-ancestors 'none'`).
  User content is rendered as text only.
- Per-user sliding-window rate limiting on message sends, over both STOMP and REST.
- CodeQL, Dependabot and dependency review run on every change.

The design rationale is in [ADR-0005](docs/adr/0005-websocket-authentication-and-csrf.md).
