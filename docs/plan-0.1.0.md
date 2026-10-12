# Initial 0.1.0 release plan

Status: revised plan for user-approved in-session generation. Stages 1–2 are implemented/accepted; stages 3–6 below are plans only. No release or new generation implementation is claimed.

## Goal

Install a Claude Code plugin that asynchronously creates useful provenance-bearing observations and reflections, persists them in Claudiom's own format, and independently lists/recalls them. Claudiom owns orchestration, validation, persistence, listing and recall; Claude Code executes the in-session generation agent.

## Memory concepts

An **observation** is a concise, source-grounded note about something worth retaining from the conversation: a decision, constraint, explicit preference, verified result or unresolved finding. It cites the exact parent/subagent source records and preserves attribution, uncertainty and time-sensitive context. It is not a transcript dump or a claim that every assistant statement is true.

A **reflection** synthesizes one or more committed observations into useful higher-level understanding: a stable constraint, recurring pattern or lesson. It references its supporting observation IDs so recall can trace the conclusion back to source records. It must not introduce unsupported facts or present an inference as a verified result.

Example observation: “The user chose Node 22.19 as the minimum runtime.” Example reflection, supported by that decision and installation evidence: “Consumers need Node, but build-time Java must not become an installation requirement.”

Generation can legitimately produce neither. A successful empty result still commits **coverage**, which records processed source progress without inventing a memory. Coverage is bookkeeping, not an observation or reflection.

See [memory-model.md](memory-model.md) for selection rules, examples, provenance, correction and recall requirements. These semantics guide the native schema and model prompts; implementation is still pending.

## Deliver in small PRs

### 0. Bootstrap (this checkout)

- [x] Ahead-of-time ESM CLI, minimal JavaScript boundary, committed runtime.
- [x] ClojureScript unit tests, packaged Node checks and clean-consumer tarball test.
- [x] Node 22.19/24 CI, release checks/checksum, development and agent guidance.
- [x] GPL-3.0-only licensing and packaged license text.
- [ ] Create remote, choose visibility and configure history-preserving protected-main process.

### 1. Native persistence contract and reader

Freeze the schema and paths before adding generation. Define event/memory identity, session/main/subagent citations (UUID plus a native locator for UUID-less records), first-definition/conflict semantics, tombstones and reflection supports. A generation commit carries both coverage and its accepted memories; empty output still advances durable coverage. Define append framing, durable acknowledgement, partial-tail repair and bounded reads explicitly.

Build the smallest private-file writer/fold plus `list` and `recall <id>` CLI. Use synthetic fixtures for restart, conflicting replay, drop-before-definition, incomplete tail, malformed input, owner-only/no-symlink safety and coverage recovery. Native logs are directly usable by the producer's own listing and recall interfaces; no export step is required.

### 2. Claude source reader and context selection

Read explicitly provided parent/subagent locators, preserve source/citation provenance and detect mutations/partial appends. The throwaway structural scan and user-run manual checks are recorded in [Claude transcript findings](claude-transcripts.md); no repository probe slice is needed. D1 prefix/incarnation rules are frozen, hook paths are absolute, missing subagent sources are no-ops, and attachment records follow the user-approved allowlist in [003](issues/003-claude-source-reader-and-context-selection.md). Select only bounded relevant content and verify recalled citations read-only; never ship private transcripts as fixtures. The user marked 003 ready and authorized implementation; the source/selection/verification slice is now accepted after implementation re-review.

### 3. Store amendment, lock, registry and hook registration — issue 004

Amend v1 to projects/<claude-project-key>/<session_id>/events.jsonl, raw safe 1–255-byte components and hook-derived key, no encoding algorithm. CLI cwd/--project maps registry; URN recall uses bounded session-name search (256 projects × 256 sessions). Pre-release hashed layout is unsupported/ignored, no migration/deletion. Empty reasons are routine/no_decisions/duplicate. Issue 004 supplies concrete mappings/bounds.

Store-wide O_EXCL lock: wait 2 seconds, stale 30 seconds, ownership-token release only, accepted paused-owner race can corrupt its isolated session log. No fencing guarantee. Protect native fold/validate/append/fsync/repair and per-session registry/shared lease/backoff updates, never model work. Registry one atomic file/session, 30-day retention; SessionStart captures CLAUDE_CODE_ENTRYPOINT, only sdk-cli suppresses nudge. SessionEnd short wait/deadline only registers. Only SubagentStop builds subagent locators; exclude memory-writer. No discovery/inbox/worker, stage-2 code unchanged. See [004](issues/004-store-lock-registry-and-hooks.md).

### 4. In-session observation tracer bullet — issue 005

Expose next_context/record with server-owned handles, fixed citation membership, one stream at a time. Lease 5 minutes in shared XDG state; handle same expiry in MCP memory, restart expires it and record fails without writes. 005 sets 128 KiB context/256 KiB tool payload, bounded records and 16-commit/10-minute cooperative loop. Locked contiguity/replay normally determine correctness subject to accepted stale-break race. Successful empty coverage requires fixed enum; no-context/invalid work commits nothing.

`/claudiom:remember` supplies fresh `${CLAUDE_SESSION_ID}` and delegates to background `claudiom:memory-writer`, frontmatter `model: haiku`. The writer sees only the two producer tools (plus observed built-in handback), has no arbitrary file/tool access, and may not drop/supersede memories. No provider SDK/fetch adapter, independent credentials or model configuration for 0.1.0.

Stop registers/folds/stats only, no transcript reads. Above 40,000 uncovered bytes, nudge naming skill when not stop_hook_active, not sdk-cli and no active shared lease. SessionStart's other/empty/absent entrypoint permits nudges, not fail closed; 005 opt-in -p use must confirm sdk-cli. Shared XDG backoff defaults 15 minutes until lease/commit reset. Genuine errors exit 0/empty stdout, never exit 2, timeout 5 seconds. SessionEnd only registers; manual remember works headless. See [005](issues/005-in-session-memory-writer.md).

Completeness is best-effort while sessions are live. The user-run 2.1.296 spike found that background work survives main-turn end but not `/exit`; uncovered progress stays visible. A nudge may be ignored or classifier-blocked without repeated per-turn pressure. Normal tests use synthetic MCP calls/fake delegation only; acceptance requires opt-in real use.

### 5. Reflections and consolidation — issue 006

After observation work, the same writer calls `next_context(session_id, lane: "reflection")` when the session reaches a configurable active-observation limit (default 40). Reflection handles hold only committed observations; supports must belong to that table. A committed reflection archives exactly its validated supports as implied `consolidated` fold status, without a new event kind. Definitions/citations remain recallable through reflections. Preserve independent reflection coverage, explicit corrections and support-change handling; no cross-session consolidation. See [006](issues/006-reflections-and-consolidation.md).

### 6. MCP read tools, setup, packaging and release — issue 007

Expose explicit read-only MCP list/recall alongside CLI project routing and stage-2 citation verification. No model/tool generation during reads or automatic provenance/prompt injection. Package the runtime, agent, skill, hooks and MCP config inside the plugin cache boundary, with Node-only consumption and GPL-3.0-only licensing. `/claudiom:setup` documents permissions for exactly next_context/record; no blanket MCP allow rule. The observed first session used auto mode, so permission-prompt/persistence behavior remains unverified and is not a universal installation claim.

Accept opt-in real-session registration -> remember/nudge -> background writer -> native list/recall -> restart, including empty reasons, cancellation/session exit, duplicate calls, lock/lease faults, parent/subagent attribution, compaction/resume and consolidated support recall. Record uncovered progress and evidence limitations honestly. No detached-worker survival or direct-provider spend probe is required by the new design; Claude Code owns generation/billing.

Only then bump package/lockfile/plugin manifest to 0.1.0 through a final PR, date changelog, regenerate artifacts, pass hosted CI and exact tarball/plugin/cache smoke tests. Linus merges without squash; tag merged validated main only with explicit approval. See [007](issues/007-mcp-reads-setup-packaging-and-release.md).

## Out of scope

External integrations, databases, non-Claude hosts, alternative agent harnesses, universal provider abstraction, semantic/global retrieval, separate login/credential discovery, transcript restoration, automatic injection, polling daemons, Windows support claims.

## Acceptance gates

- Generation, persistence and recall work as a self-contained Claude Code plugin/CLI.
- Observations cite source records; reflections cite committed observations; recall exposes that provenance.
- Selection preserves attribution and uncertainty; unsupported output is rejected rather than saved as fact.
- Explicitly installed/permissioned in-session generation; retrieval works with no model credentials.
- Read-only Claude source files; private content is not exposed in diagnostics.
- Durable empty-result reason/coverage and post-commit replay safety; failed or cancelled agent work never covers source.
- Parent/subagent citations independently resolve or report missing source.
- Hooks do not await models; Stop nudges/backoff, locks/leases and diagnostics are bounded.
- Runtime/install needs Node only; normal tests need no Claude, provider or database.
- Real in-session agent/tool/permission and restart evidence is opt-in and recorded before release.

## Concrete defaults and deferred evidence

The 2026-10-12/13 user-approved redesign supersedes provider/client/credentials/worker decisions. Generation is an in-session Haiku plugin agent through MCP; storage mirrors Claude projects; writes are locked; registry/leases are operational; consolidation is implied by committed reflection supports. URNs and accepted stage-2 source behavior remain unchanged. A corrupt target log blocks that target, not unrelated sessions, and explicit repair still refuses complete corruption.

The scoped 2.1.296 spike and pending -p signal confirmation are recorded in [integration](claude-integration.md). Issues 004–007 now specify concrete state/lock/handle/routing/reflection defaults, not freeze-later questions. Model configuration/provenance injection/classifier/fresh-config/manual permission-persistence work remain deferred. Explicit readiness still gates code; publication/setup/version acceptance remains gated on 007.