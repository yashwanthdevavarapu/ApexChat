# Contributing to ApexChat

Thanks for helping out! This guide covers setup, the workflow we use, and what a pull request needs
before it can be merged.

## Development setup

| Tool | Version | Needed for |
|---|---|---|
| JDK | 17+ | everything (Maven is provided by `./mvnw`) |
| Node.js | 18+ (CI uses 20) | Playwright e2e tests and screenshots |
| Docker | any recent | `PostgresIT` and `docker compose` (optional locally) |

```bash
./mvnw spring-boot:run                 # app on http://localhost:8080 with in-memory H2
cd e2e && npm ci && npx playwright install chromium
```

## Workflow

1. **Open or claim an issue** first for anything non-trivial, so the approach can be agreed early.
2. **Branch** from `main`: `feat/read-receipts`, `fix/presence-race`, `docs/adr-0011`.
3. **Commit** using [Conventional Commits](https://www.conventionalcommits.org/):
   `feat(presence): show last-seen time`, `fix(stomp): …`, `test: …`, `docs: …`, `ci: …`.
4. **Open a PR**. The template has a checklist. CI must be green and one CODEOWNER must approve.
5. We **squash-merge**, so the PR title becomes the commit message.

## Before you push

```bash
./mvnw spotless:apply      # google-java-format; CI fails on unformatted code
./mvnw verify              # unit + integration tests + 80% coverage gate
cd e2e && npm test         # when UI or messaging behaviour changed (builds on target/apexchat.jar)
```

## Guidelines

- **Tests come with the change.** Bug fixes include a test that fails without the fix. STOMP
  behaviour belongs in `StompMessagingIT`. Use its probe-based `awaitReady` helper, never `sleep`.
- **Security-sensitive areas:**
  - `WebSocketSecurityConfig` (the destination allow-list)
  - `SecurityConfig` (CSP and auth)
  - anything that renders user content in `chat.js`, which must use `textContent`, never
    `innerHTML`

  Changes here need a test that proves the protection still holds.
- **Architectural changes** (protocols, broker, persistence model, auth) need an
  [ADR](docs/adr/README.md).
- **UI changes:** keep `data-testid` attributes stable, and regenerate screenshots with
  `npm run screenshots` if the look changes.
- Keep the schema portable across PostgreSQL and H2 ([ADR-0009](docs/adr/0009-portable-schema-flyway-h2-postgres.md)).
  Never edit an applied migration; add `V2__…`.

## Releasing (maintainers)

1. Update [`CHANGELOG.md`](CHANGELOG.md) and the version in `pom.xml`.
2. Tag and push the tag: `git tag v1.1.0 && git push origin v1.1.0`.
3. `release.yml` verifies the build, publishes the GitHub Release with the jar, and pushes
   `ghcr.io/<owner>/apexchat:{1.1.0,1.1,latest}`.

## Code of conduct

Participation is governed by our [Code of Conduct](CODE_OF_CONDUCT.md).
