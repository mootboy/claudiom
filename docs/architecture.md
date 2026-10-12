# Architecture

Status: bootstrap implemented; producer behavior planned.

## Ownership

Claude Code owns its heterogeneous parent/subagent transcripts. Claudiom reads them and owns native generation, storage, listing, recall and source coverage. Adam independently owns any future integration adapter. No Adam imports, Neo4j, Adam protocol exports, notifications or worker commands.

Use Node standard library plus compiled ClojureScript. No Pi Durable, generic adapter framework, daemon service or polling layer for 0.1.0 unless a measured requirement warrants it.

## Planned execution

```text
Claude lifecycle hooks
  -> bounded durable locator-only producer inbox
  -> one detached, recoverable, serialized producer worker
  -> read-only transcript/context selection
  -> bounded explicit-credential model request
  -> atomic native memory + coverage commit
  -> independent list / recall / MCP reader
```

Hooks never wait for generation. Sessions fail independently; retries are bounded and visible. PID liveness alone is not proof of progress: model calls have timeouts/cancellation and worker runs have bounded work and observable completion/lease release.

Model work is disabled until affirmative opt-in. Default direction: explicit approved provider credentials, not unattended Claude subscription reuse. Inherited-login CLI execution requires a separate policy decision, consent and characterization.

## Authority and recovery

Native append-only logs are generation authority. Commit successful observations/reflections and corresponding coverage in one event; a valid no-memory outcome commits coverage with empty memory. Fold that log on restart before consulting disposable checkpoints. Failure/cancellation never commits coverage. A crash before commit may regenerate and incur another call; after durable commit, retry must not duplicate logical generation.

Native persistence, citation identity, partial-tail recovery and exact limits must be frozen and fixture-tested in the first implementation increment. Do not copy Adam's protocol rules without a producer requirement.

Proposed paths: XDG data for authoritative native logs; XDG state for locators/checkpoints/leases/content-free bounded diagnostics; XDG config for explicit consent/provider settings. Require absolute roots, owner-only non-symlink storage and hashed session path components. Initial supported platform: Linux; macOS/Windows require separate detached-boundary evidence.

## Claude context

Preserve raw source locators and session/stream provenance. Explicit UUID parenting is not sufficient alone: account for parallel request/tool links, compaction logical ancestry and directly located subagents. Resolve native file paths against the call record's cwd. Summaries and Bash/prose are not deterministic file evidence. Never append custom data to Claude transcripts.

## Retrieval

Independent listing and recall are required with Adam absent. Native citations must support source expansion while clearly reporting missing transcripts. MCP retrieval is explicit/read-only; no automatic prompt injection in 0.1.0. Native logs remain usable without model credentials.
