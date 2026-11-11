# 0009. One Flyway schema for H2 (dev) and PostgreSQL (prod)

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

We want zero-setup local runs and fast tests. Production still needs a real, durable database.
Hibernate's `ddl-auto=update` is convenient but drifts silently and cannot express constraints we
rely on.

## Decision

- **Flyway** owns the schema (`db/migration/V1__init_schema.sql`). Hibernate runs with
  `ddl-auto=validate` and fails fast on any mismatch between entities and tables.
- The SQL is written to run unchanged on **PostgreSQL 16** and **H2 in PostgreSQL mode**. It uses
  identity columns, `TIMESTAMP WITH TIME ZONE`, and a `CHECK` that a message targets exactly one of
  a room or a recipient.
- The default profile uses in-memory H2. The `postgres` profile (used by `docker-compose.yml`)
  points at PostgreSQL through `DB_URL`, `DB_USER` and `DB_PASSWORD`.
- `PostgresIT` runs the migrations and the persistence path against a real PostgreSQL container
  (Testcontainers). It runs in CI and is skipped automatically on machines without Docker.

## Consequences

- `java -jar apexchat.jar` works with no setup. Data resets on restart, which is documented.
- SQL must stay within the shared dialect. The Postgres integration test catches violations in CI.
