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

Amend native v1 to `projects/<claude-project-key>/<session_id>/events.jsonl`, with strictly validated raw components. Derive Claude's project key from the parent hook transcript path, never reimplement its encoding. Scope list/CLI by project; URN recall routes by session directory name across projects. The 256-session limit becomes per-project. Freeze legacy handling, cross-project ambiguity/scan bounds, empty-result reason schema and consolidation fold semantics before implementation.

Add a store-wide `O_EXCL` write lock with bounded wait and age-based stale breaking, held only for fold/validation/append/fsync and explicit repair. Register explicit session/parent/subagent locators in private XDG state using SessionStart, Stop, SubagentStop and SessionEnd. Only SubagentStop produces subagent locators; never register `claudiom:memory-writer` itself. No discovery, inbox or detached worker. Stage-2 source code remains unchanged. See [004](issues/004-store-lock-registry-and-hooks.md).

### 4. In-session observation tracer bullet — issue 005

Expose MCP `next_context(session_id)` and `record(handle, memories, reason)` with server-owned expiring handles and bounded citation membership. Parent plus registered subagents are processed one stream at a time. A roughly five-minute range lease avoids duplicate work; locked contiguity/replay validation is authoritative. Successful empty results require a fixed-enum reason with coverage; failures/cancellation commit nothing.

`/claudiom:remember` supplies fresh `${CLAUDE_SESSION_ID}` and delegates to background `claudiom:memory-writer`, frontmatter `model: haiku`. The writer sees only the two producer tools (plus observed built-in handback), has no arbitrary file/tool access, and may not drop/supersede memories. No provider SDK/fetch adapter, independent credentials or model configuration for 0.1.0.

Stop registers/folds/stats only, with no transcript reads. Above summed uncovered progress (default 40 KB ≈ 10K tokens), nudge using `decision: block` with a fixed reason naming the skill, only when not `stop_hook_active`, not `sdk-cli` and with no active lease. At most one nudge per session per configurable interval (default 15 minutes) until lease/commit progress appears. Any error exits 0 with empty stdout; never exit 2; timeout 5 seconds. SessionEnd only registers. Manual remember remains available in headless sessions. See [005](issues/005-in-session-memory-writer.md).

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

## Open decisions to resolve in the relevant PR

The 2026-10-12/13 user-approved redesign supersedes provider/client/credentials/worker decisions. Generation is an in-session Haiku plugin agent through MCP; storage mirrors Claude projects; writes are locked; registry/leases are operational; consolidation is implied by committed reflection supports. URNs and accepted stage-2 source behavior remain unchanged. A corrupt target log blocks that target, not unrelated sessions, and explicit repair still refuses complete corruption.

The completed user-run spike on Claude Code 2.1.296 informs the contract; its scoped results and deferred checks are in [Claude integration](claude-integration.md). Model configurability, SessionStart provenance injection, classifier rules, fresh-config retesting and manual-mode permission-persistence testing are deferred for the single-user 0.1.0. Exact lock/registry/handle bounds, legacy-layout treatment and reflection-feed mapping are implementation-contract details to freeze in their issues, not new authorization. Publication/setup/version acceptance remains gated on 007.
