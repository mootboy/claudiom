# Architecture

Status: native persistence and stage-2 source reading/selection/verification are implemented and accepted. The in-session generation redesign below is user-approved planning, not implemented. Stage-2 code is reused unchanged.

## Ownership and execution

Claude Code owns transcripts and model execution. Claudiom owns bounded source selection, native persistence, listing/recall, coverage, locator registration and validation. There is no external memory service, provider adapter, separate credential store, detached worker, inbox or daemon.

```text
SessionStart / Stop / SubagentStop / SessionEnd
  -> private XDG-state locator registry (explicit session + parent/subagent locators)
Stop eligibility (registry + native fold + stat only)
  -> occasional decision:block nudge: run /claudiom:remember
/claudiom:remember (${CLAUDE_SESSION_ID})
  -> background plugin agent claudiom:memory-writer (model: haiku)
  -> MCP next_context(session_id[, lane]) -> bounded context + opaque handle
  -> MCP record(handle, memories[, reason])
  -> locked revalidation + atomic native memory/coverage commit
  -> independent read-only CLI / MCP retrieval
```

The same skill can be invoked manually, including in headless sessions. No standing SessionStart instruction is injected. For 0.1.0 the writer's model frontmatter is literal `haiku`; configurable models, provider credentials, direct API requests and provider-cost reporting are no longer part of the design. Claude Code controls model execution and billing; installation/setup discloses in-session/background use and tool permissions, without promising a spend ceiling.

## Best-effort lifetime and nudges

Generation happens only while a session is live. The user-run Claude Code 2.1.296 spike confirmed background work survives a main-turn end but is killed by `/exit`. This is accepted: uncovered progress remains visible, not silently covered or rescued by a detached worker.

Stop performs registration, fold and stat only, never transcript reads or model work. Nudge only above summed uncovered progress across registered streams (default 40 KB, approximately 10K estimated tokens), not during `stop_hook_active`, not for observed/configurable `sdk-cli` headless classification, and with no active range lease. Use a fixed skill-naming reason, at most one nudge per session per configurable interval (default 15 minutes) until lease/commit progress appears. Errors fail open: exit 0, empty stdout; never exit 2. SessionEnd only registers, with no final generation promise.

Only SubagentStop builds subagent locators. Other events build parent locators even when agent fields occur. Ignore `agent_type: claudiom:memory-writer` SubagentStop entirely, preventing self-ingestion. Internal empty-type events without a usable source are normal no-ops. Read the parent plus all registered eligible subagents, one stream per observation handle; the accepted stage-2 safety/missing-source rules still apply.

## Authority, concurrency and trust

Append-only native logs are authoritative. Registry, leases, nudge timestamps and handles are operational state, never coverage authority. Successful empty results carry a fixed-enum reason with their atomic coverage commit; cancellation, invalid output, expired/stale handles and failures advance nothing. A durable frame remains authoritative after lost acknowledgement.

Require a store-wide `O_EXCL` write lock with bounded wait and age-based stale breaking (not PID inference), held only for fold/validate/append/fsync, never for model work. Apply it to native writes and explicit repair. A best-effort range lease of about five minutes reduces duplicate work; contiguity and locked validation provide correctness, not the lease. Freeze/test stale-owner races before claiming exclusion guarantees.

The MCP server holds an opaque expiring handle table containing session, stream/lane, incarnation, range, prefix evidence and citation/support membership. `record` accepts proposed text/references, not arbitrary source paths or forged coverage; it revalidates the handle and native invariants under the lock, all-or-nothing. Real citations do not prove truthful text: misleading interpretation is an explicitly accepted, traceable risk. No model-facing drop or supersede tools.

## Storage and project identity

Planned authoritative layout: `$XDG_DATA_HOME/claudiom/v1/projects/<claude-project-key>/<session_id>/events.jsonl`. The project key is `basename(dirname(parent transcript_path))` from hooks; never reproduce Claude's encoding from cwd. Raw session IDs and project keys are strictly validated single path components, not blindly joined. This amends implemented hashed-session storage; migration/legacy handling and exact validators must be frozen in issue 004, with no implicit deletion.

Use private absolute XDG-state storage for the locator registry, leases and nudge timestamps. Map `CLAUDE_PROJECT_DIR` through the registry for MCP scope; CLI uses cwd/`--project`. URN recall locates the named session directory across projects, failing on ambiguous identity rather than choosing arbitrarily. The 256-session scan limit is per project; cross-project routing requires its own bounded scan contract. Owner-only/no-symlink native storage remains mandatory; citation source safety is unchanged.

## Reflections, consolidation and retrieval

After the observation loop the same writer asks for reflection context. Default eligibility is 40 active observations in the session. Reflection handles contain only committed observations and permit supports only from that table. A committed reflection implies consolidation of exactly its validated supports: their status is `consolidated`, they leave active listings and remain historically recallable through the reflection. No new consolidation event kind or cross-session synthesis.

Read-only CLI and MCP tools use native memories without model calls, credentials or generation. Recall retains stage-2 bounded citation verification; no automatic prompt injection. Store/layout changes, reflections, setup and release remain unimplemented plans in issues 004–007.

## Implementation constraints

Application behavior is ClojureScript compiled for Node with standard-library boundaries, not an additional agent harness. Reads precede pure processing and writes; validate with specs and interface-first synthetic tests. Linux initially; normal tests never access host transcripts, credentials, Claude hooks or background agents. Real-use acceptance is separately opt-in. Preserve history and leave PR merges/tags to the user.
