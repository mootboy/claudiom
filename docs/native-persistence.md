# Native persistence contract v1

Status: initial writer/fold and CLI reader implemented for issue [002](issues/002-native-persistence-and-reader.md); acceptance hardening remains in progress. Applies to Linux initially. All examples below are synthetic.

## Storage and bounds

Authoritative logs live at `$XDG_DATA_HOME/claudiom/v1/sessions/<sha256(session UTF-8)>/events.jsonl`. If XDG_DATA_HOME is unset, use `$HOME/.local/share`; reject empty or relative explicitly configured roots. HOME must be absolute when used. No configuration, state directory or credentials are needed for retrieval.

Create Claudiom-owned directories with mode 0700 and regular log files with mode 0600. Validate ownership against the effective UID and reject group/other permissions on Claudiom-owned entries. Reject symlinks in every existing path component, non-directory ancestors and non-regular log files. Do not chmod arbitrary existing directories. Shared XDG ancestors need not be private; Claudiom-owned entries must be. Open logs using no-follow flags and verify opened file identity against the checked entry. Do not claim protection against a malicious process running as the same UID or root.

Limits for v1: 1 MiB per framed event including LF, 16 MiB per session log, 256 memories per generation event, 16 KiB UTF-8 per memory text, 256 citations/supports per memory, 4 KiB UTF-8 per locator path, 256 bytes UTF-8 per identifier, and 256 session logs per invocation. Exceeding a limit fails the operation; never return a silently truncated fold. Rotation/compaction is not part of this slice. Check sizes before allocating whole-file reads, then enforce limits again on bytes actually read.

## Wire format

Each event is one UTF-8 JSON object followed by LF. No blank lines. Require strict UTF-8 and reject duplicate JSON keys. An unterminated final line is uncommitted even if it parses as JSON. An invalid LF-terminated line is corruption, not a repairable tail. Reject unknown versions, event kinds and fields. Validate with Clojure specs plus explicit cross-record invariants.

Every event has `version` (1), `event_id`, `session_id`, `recorded_at` (UTC RFC3339 timestamp), and `kind`. IDs are nonempty strings bounded as above; generated IDs should be UUIDs. Memory IDs are unique across sessions; a conflicting cross-session ID is an error rather than an ambiguous recall result. Session IDs are opaque source identities, never path components.

### Generation

A `generation` event adds `coverage` and `memories` (an array, possibly empty). Coverage and all accepted memories form one commit. No per-memory partial acceptance in the writer: invalid input rejects the entire event.

Coverage has `lane` (`observation` or `reflection`), `input_id` and `from`/`to` nonnegative safe integers with `from < to`. Its identity is `(session_id, lane, input_id)`. Observation input IDs identify a source stream incarnation; progress units are byte offsets at complete-record boundaries. Reflection input IDs identify a stable ordered observation feed; progress units are feed positions. The producer must establish those identities and boundaries; the store does not infer them from Claude fields.

The first range starts at zero; subsequent ranges start at the prior committed `to`. Reject gaps, overlaps, regressions and a new event claiming previously covered work. Exact event replay is handled before coverage checks. Independent lanes and input IDs advance independently. Failed or cancelled generation produces no event.

Each memory has `id`, `kind`, `text`, and `source_time` (UTC RFC3339 or null when unavailable). Session and recording time are inherited from its generation event. An observation adds a nonempty `citations` array. A reflection adds a nonempty `supports` array of observation IDs, with no duplicates. An observation-lane commit accepts only observations; a reflection-lane commit accepts only reflections.

Each citation contains `stream` (`parent` or `subagent`), `agent_id` (null for parent, nonempty for subagent), `path` (absolute source path), `source_id` (nonempty UUID/string or null), and `locator`. The locator has `input_id`, `start`, `end` and `sha256`: a nonempty half-open byte range and SHA-256 of its exact bytes. Native locators are required even when source IDs exist. Source IDs are metadata, not sufficient proof that a file still contains the cited bytes. The digest detects same-size in-place mutations that file identity/size checks alone miss. No transcript content is embedded in a citation.

Reflection supports must already be committed observations in the same session, not reflections or unknown IDs. Dropped observations remain valid historical supports. The producer separately validates citation membership in selected context; the native writer can validate structure but cannot establish semantic truth or inspect real source content in this milestone.

Synthetic empty-result event:

```json
{"version":1,"event_id":"commit-1","session_id":"synthetic-session","recorded_at":"2026-10-12T12:00:00Z","kind":"generation","coverage":{"lane":"observation","input_id":"synthetic-parent-1","from":0,"to":100},"memories":[]}
```

### Lifecycle

A `drop` event adds only `memory_id`. It hides that ID from active listing, retaining its first definition and provenance. Drop-before-definition creates a pending tombstone: a later definition remains dropped. Repeated drops with distinct event IDs are harmless; unknown dropped IDs are not recallable until defined.

A `supersede` event adds `memory_id`, `replacement_id` and `reason` (nonempty bounded text). Both definitions must already exist in the same session, have the same kind, and differ in ID. Reject cycles and a second different replacement for an already superseded ID. The old ID becomes inactive and recall exposes the link. New definitions arrive through generation, not inline lifecycle replacements. Repeated identical supersession is harmless.

Corrections never edit text in place. Corrected observations can invalidate reflection conclusions: expose affected reflections as `support_changed` and omit them from active listing until explicitly dropped or superseded. Preserve their historical supports for recall. Dropping a support alone does not assert its content was false and does not invalidate a reflection.

## Folding and replay

Read the bounded authoritative log up front. Fold with pure processing, producing definitions, lifecycle state, event identities and coverage. Validate a proposed append against that fold, then perform writes at the end. Disposable checkpoints never override the log.

For the same event ID, equality means identical parsed event values (object key order is irrelevant, array order is significant). Exact replay is a no-op. Any changed value is a conflict. An existing memory ID may recur only with an identical first definition, including inherited session/recording time; otherwise reject. No later definition overwrites the first. Fold validation is all-or-nothing: corruption yields no partial success or coverage result.

## Append, acknowledgement and repair

The writer interface is single-writer per session. It does not introduce a worker or lock/lease subsystem in this slice; callers must serialize access. Reads during an append may report an incomplete tail and retry; they must not repair or mutate it. Concurrent-writer support requires the serialized worker increment, not a claim that an append syscall makes whole transactions safe.

Write the complete frame with a loop handling short writes, then fsync the file. For newly created entries, fsync containing directories so acknowledged storage survives restart. Acknowledge only after these operations succeed. In a write/fsync error, report an uncertain outcome and require recovery; never optimistically update coverage. Re-read the log on retry. A crash after a durable frame but before acknowledgement is resolved by event replay without another logical generation.

Only a writer/recovery operation may truncate an unterminated final tail back to the last LF, then fsync the repaired file before appending. Never repair a malformed complete frame or skip corruption. A complete valid frame found after an uncertain write is authoritative during recovery; a missing/incomplete frame is not. This does not prevent a provider call being repeated after a crash before commit.

## CLI reader

- `list [--session <id>]`: JSON array, active memories sorted by `(recorded_at, id)` ascending. Without a session filter, scan up to the session limit; do not create storage. A missing store is an empty list.
- `recall <id> [--session <id>]`: JSON object with the first stored definition, lifecycle status, correction links and provenance. For reflections, include supporting stored observations and their lifecycle statuses. Missing memory exits 1; invalid arguments or unsafe/corrupt storage exit 2. Diagnostics are fixed, content-free messages on stderr.
- Both commands work without credentials, models or source transcripts. Normal stdout is machine-readable JSON; no private paths/content in error diagnostics.
- Citation expansion is deferred to the source-reader increment. Return `source_status: "not_checked"` alongside stored citations; do not claim a file is missing or resolved without inspecting it. Stored memory remains accessible regardless of source availability.

## Required evidence

Issue 002 lists the interface/restart/safety acceptance cases. Add parameterized tests of actual private temporary files and packaged readers, with property tests where useful for pure fold/replay invariants. No host transcripts, real providers, hooks or background processes. This document specifies behavior, not proof that the implementation already provides it.
