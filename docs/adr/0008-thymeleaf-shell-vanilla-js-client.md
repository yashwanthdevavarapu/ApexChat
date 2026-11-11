# 0008. Thymeleaf shell + framework-free JS client

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The UI is a handful of screens: sign in, register, and one chat view. The chat view is driven by
STOMP events. We want a single deployable artifact, no Node toolchain in the production build, and
a strict Content-Security-Policy.

## Decision

- **Thymeleaf** renders the login and registration pages server-side, including validation errors,
  so they work without JavaScript. It also renders the chat shell, embedding the username and CSRF
  token as `data-*` attributes.
- The chat client is a single **ES module, `static/js/chat.js`, with no framework and no build
  step**. It does targeted DOM updates, groups messages and adds day dividers, routes conversations
  via the URL hash (`#room/general`, `#dm/alice`), and tracks unread counts.
- All dynamic text goes through `textContent`. Links are created with `createElement('a')` and
  `rel="noopener noreferrer"`. `innerHTML` is never used with user data.
- Styling is one stylesheet with CSS custom properties, which gives dark and light themes through
  `prefers-color-scheme`, a responsive layout with an off-canvas sidebar under 820 px, and
  `prefers-reduced-motion` support.
- Stable `data-testid` attributes form the contract for the Playwright suite.

## Alternatives considered

- **React or Vue SPA.** This means a second build pipeline and a larger attack surface for little
  gain at this UI size. It is worth revisiting if the client grows to threads, reactions and file
  uploads.
- **htmx.** This is good for server-driven fragments, but the core of this app is client-side
  reaction to socket events, which htmx's model fits less naturally.

## Consequences

- `mvn package` produces the whole product, and CSP stays `script-src 'self'` with no exceptions.
- The client is about 700 lines. Beyond that size, a component framework would pay for itself.
