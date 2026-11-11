# 0001. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

ApexChat makes several non-obvious choices: the messaging protocol, the broker, how presence is
computed, and how WebSocket connections are authenticated. Without a record, the reasoning behind
them is lost. Future contributors then either repeat the investigation or "fix" something that was
deliberate. [ADR-0007](0007-message-ordering.md) is a good example: it explains why two
seemingly helpful Spring flags are switched off.

## Decision

We keep Architecture Decision Records in `docs/adr/`, numbered sequentially, using the
Context / Decision / Alternatives / Consequences structure in [`template.md`](template.md).
Every pull request that changes an architectural decision adds or supersedes an ADR. The PR
template includes a checkbox for this.

## Consequences

- Decisions are reviewable in the same PR as the code that implements them.
- Superseded ADRs stay in the repository, so the history of a decision remains visible.
- There is a small documentation cost for each significant change.
