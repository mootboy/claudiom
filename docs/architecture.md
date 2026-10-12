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

Stop performs registration, fold and stat only, never transcript reads or model work. SessionStart records CLAUDE_CODE_ENTRYPOINT: exactly `sdk-cli` suppresses nudges; every other/empty/absent value allows them subject to eligibility. Issue 005 opt-in real use confirms `-p` yields sdk-cli; that evidence is still pending. Nudge above 40,000 summed uncovered bytes, not during stop_hook_active or an active shared lease. Shared registry backoff defaults to 15 minutes, reset by a lease/commit. Genuine errors exit 0/empty stdout; absent entrypoint is not an error. Never exit 2. SessionEnd only registers.

Only SubagentStop builds subagent locators. Other events build parent locators even when agent fields occur. Ignore `agent_type: claudiom:memory-writer` SubagentStop entirely, preventing self-ingestion. Internal empty-type events without a usable source are normal no-ops. Read the parent plus all registered eligible subagents, one stream per observation handle; the accepted stage-2 safety/missing-source rules still apply.

## Authority, concurrency and trust

Append-only native logs are authoritative. Registry, leases, nudge timestamps and handles are operational state, never coverage authority. Successful empty results carry a fixed-enum reason with their atomic coverage commit; cancellation, invalid output, expired/stale handles and failures advance nothing. A durable frame remains authoritative after lost acknowledgement.

Store-wide owner-only O_EXCL lock: wait 2 seconds, retry 25 ms, stale-break at 30 seconds by age, not PID. Hold for native fold/validate/append/fsync/repair and shared registry/lease/backoff updates, never model work. Ownership token only ensures release checks the current lock token; no fencing protocol. **Accepted 0.1.0 risk:** a paused owner can overlap a stale breaker and corrupt its affected session log; that log refuses further writes, unrelated logs remain isolated. Do not claim strict exclusion. Shared 5-minute leases reduce duplicate work; normal contiguity/validation, not leases, determine accepted progress.

MCP-server memory holds 128-bit opaque handles (max 256 live), expiring with the 5-minute lease; restart expires them, so record fails without writes. Handle binds session/project/stream/lane/incarnation/range/prefix/membership. Identical retries within expiry replay the handle's event; native logs recover committed work after restart. Model proposes text/references, not paths/coverage. Real citations do not prove truthful interpretation: accepted traceable risk. No model-facing drop/supersede.

## Storage and project identity

Planned logs: `$XDG_DATA_HOME/claudiom/v1/projects/<claude-project-key>/<session_id>/events.jsonl`; key from basename(dirname(parent transcript_path)), never recreated encoding. Raw Unicode-scalar components are 1–255 UTF-8 bytes, excluding `.`, `..`, separators, NUL/C0/DEL; no normalization. The pre-release hashed layout is unsupported/ignored, not migrated or deleted.

Shared private XDG-state registry: one file per session, atomic replacement under the store lock; leases and nudge timestamps live there so separate Stop processes can check them. Retain 30 days, 1 MiB/file, 256 subagents/session, 4096 entries; cleanup never touches logs. MCP maps CLAUDE_PROJECT_DIR through registry; CLI cwd/--project uses recorded project mapping. URN directory-name routing is bounded to 256 projects × 256 sessions, failing on ambiguity/limits. Owner-only/no-symlink native rules and source safety remain. Concrete selector/state/run defaults are in issues 004–007.

## Reflections, consolidation and retrieval

After the observation loop the same writer asks for reflection context. Default eligibility is 40 active observations in the session. Reflection handles contain only committed observations and permit supports only from that table. A committed reflection implies consolidation of exactly its validated supports: their status is `consolidated`, they leave active listings and remain historically recallable through the reflection. No new consolidation event kind or cross-session synthesis.

Read-only CLI and MCP tools use native memories without model calls, credentials or generation. Recall retains stage-2 bounded citation verification; no automatic prompt injection. Store/layout changes, reflections, setup and release remain unimplemented plans in issues 004–007.

## Implementation constraints

Application behavior is ClojureScript compiled for Node with standard-library boundaries, not an additional agent harness. Reads precede pure processing and writes; validate with specs and interface-first synthetic tests. Linux initially; normal tests never access host transcripts, credentials, Claude hooks or background agents. Real-use acceptance is separately opt-in. Preserve history and leave PR merges/tags to the user.
