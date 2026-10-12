# Initial 0.1.0 release plan

Status: proposed implementation plan; bootstrap ready. No producer functionality or release is claimed.

## Goal

Install a Claude Code plugin that asynchronously creates useful provenance-bearing observations and reflections, persists them in Claudiom's own format, and independently lists/recalls them. It must work with Adam absent.

## Deliver in small PRs

### 0. Bootstrap (this checkout)

- [x] Ahead-of-time ESM CLI, minimal JavaScript boundary, committed runtime.
- [x] ClojureScript unit tests, packaged Node checks and clean-consumer tarball test.
- [x] Node 22.19/24 CI, release checks/checksum, development and agent guidance.
- [ ] Create remote, choose license/visibility and configure history-preserving protected-main process.

### 1. Native persistence contract and reader

Freeze the schema and paths before writing a worker. Define event/memory identity, session/main/subagent citations (UUID plus a native locator for UUID-less records), first-definition/conflict semantics, tombstones and reflection supports. A generation commit carries both coverage and its accepted memories; empty output still advances durable coverage. Define append framing, durable acknowledgement, partial-tail repair and bounded reads explicitly.

Build the smallest private-file writer/fold plus `list` and `recall <id>` CLI. Use synthetic fixtures for restart, conflicting replay, drop-before-definition, incomplete tail, malformed input, owner-only/no-symlink safety and coverage recovery. Native logs must not adopt Adam identities or require export.

### 2. Claude source reader and context selection

Read explicitly provided parent/subagent locators, preserve source/citation provenance and detect mutations/partial appends. Characterize resume, compaction, parallel tool fragments, changing cwd and subagent streams with an opt-in probe. Select only bounded relevant content; do not ship private transcripts as fixtures.

### 3. Bounded observation generation tracer bullet

One explicit provider adapter initially, default disabled. Require model/provider choice and affirmative disclosure of background source transmission. Fake-provider tests before an opt-in credentialed probe. Validate structured output, input/output/item limits, timeout/cancellation, call count and reported cost. Budget controls are guards, not guaranteed spend ceilings.

A manual `generate --transcript <path>` vertical slice must commit observations + coverage, return no-memory coverage, and list/recall after restart without another logical generation. Credential choice and exact limits are decision gates, not permission to reuse host login.

### 4. Non-blocking hooks and serialized worker

Package SessionStart, Stop, SubagentStop and SessionEnd. Hooks only validate/enqueue locators and best-effort wake the worker. SessionStart repairs; Stop/subagent completion make progress eligible; SessionEnd can flush a short remainder. Start with configurable source-progress thresholds (provisional observation 10k / reflection 20k estimated tokens).

Test wake-up loss, concurrent hooks, crash after append/before acknowledgement, stale lease, slow provider, retry exhaustion, unrelated-session progress, process shutdown and lease release. No invisible infinite retry or live-PID-only progress assumption. Bound/rotate operational logs.

### 5. Reflections and independent Claude recall

Add bounded reflections supported by committed observations; retain dropped source provenance. Provide explicit read-only MCP listing/recall plus human CLI; recall uses native stored memories and source citations. No tools/model calls during retrieval; no automatic prompt injection.

### 6. Standalone acceptance and release

With Adam absent, prove real native Claude activity -> fast hook -> background memory -> list/recall -> restart persistence. Exercise valid no-memory replay, timeout/outage, crash-after-commit, parent/subagent citations, compaction/resume and clean worker termination. User explicitly accepts background generation and real-provider spend.

Only then choose/finalize licensing/distribution, bump package/lockfile/plugin manifest to 0.1.0 in a final PR, date changelog, regenerate artifacts, pass hosted CI and smoke-test the exact release tarball/plugin. User merges, then explicitly approves a post-merge tag.

## Out of scope

Adam native-reader integration (separate Adam PR), Neo4j, Adam protocol/spool, Pi support, Pi Durable, universal provider abstraction, semantic/global retrieval, unattended subscription-login default, transcript restoration, automatic injection, polling daemons, Windows support claims.

## Acceptance gates

- Zero Adam dependency/configuration in generation, persistence or recall.
- Opt-in generation; retrieval works with no model credentials.
- Read-only Claude source files; private content is not exposed in diagnostics.
- Durable empty-result coverage and post-commit replay safety; provider failure never covers source.
- Parent/subagent citations independently resolve or report missing source.
- Hooks do not await models; worker retries and diagnostics are bounded.
- Runtime/install needs Node only; normal tests need no Claude, provider or database.
- Actual model probe, detached-worker and restart evidence are opt-in and recorded before release.

## Open decisions to resolve in the relevant PR

License/publication; native schema and exact XDG layout; UUID-less citation locator; first explicit provider/model; numerical input/output/timeout/cost limits; credential policy; Claude minimum supported version and MCP/plugin install procedure. These are not silently decided by the bootstrap.
