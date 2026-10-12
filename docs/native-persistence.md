# Native persistence contract v1

Status: initial writer/fold and CLI reader implemented for issue [002](issues/002-native-persistence-and-reader.md); acceptance hardening remains in progress. Applies to Linux initially. All examples below are synthetic.

## Storage and bounds

Authoritative logs live at `$XDG_DATA_HOME/claudiom/v1/sessions/<sha256(session UTF-8)>/events.jsonl`. If XDG_DATA_HOME is unset, use `$HOME/.local/share`; reject empty or relative explicitly configured roots. HOME must be absolute when used. No configuration, state directory or credentials are needed for retrieval.

Create Claudiom-owned directories with mode 0700 and regular log files with mode 0600. Validate ownership against the effective UID and reject group/other permissions on Claudiom-owned entries. Reject symlinks in every existing path component, non-directory ancestors and non-regular log files. Do not chmod arbitrary existing directories. Shared XDG ancestors need not be private; Claudiom-owned entries must be. Open logs using no-follow flags and verify opened file identity against the checked entry. Do not claim protection against a malicious process running as the same UID or root.

Limits for v1: 1 MiB per framed event including LF, 16 MiB per session log, 256 memories per generation event, 16 KiB UTF-8 per memory text, 256 citations/supports per memory, 4 KiB UTF-8 per locator path, 256 bytes UTF-8 per stored identifier (valid Unicode scalar sequences); public memory URNs are bounded separately at 1550 ASCII characters, and 256 session logs per invocation. Exceeding a limit rejects the affected log or write; never return a silently truncated fold. Retrieval may skip rejected sessions with explicit warnings, as described below. Exceeding the store-wide session scan limit fails the entire invocation. Rotation/compaction is not part of this slice. Check sizes before allocating whole-file reads, then enforce limits again on bytes actually read.

## Wire format

Each event is one UTF-8 JSON object followed by LF. No blank lines. Require strict UTF-8 and reject duplicate JSON keys. An unterminated final line is uncommitted even if it parses as JSON. An invalid LF-terminated line is corruption, not a repairable tail. Reject unknown versions, event kinds and fields. Validate with Clojure specs plus explicit cross-record invariants.

Every event has `version` (1), `event_id`, `session_id`, `recorded_at` (UTC RFC3339 timestamp), and `kind`. IDs are nonempty strings bounded as above; generated IDs should be UUIDs. Stored memory IDs are local to a session: different sessions may reuse the same local ID. Session IDs are opaque source identities, never path components.

### Public memory identity

`urn:claudiom` is a private, unregistered namespace used by this application; `claudiom` is not an IANA-registered URN namespace under RFC 8141. No public namespace registration or external resolver is claimed.

Retrieval exposes `id` as `urn:claudiom:<session>:<memory>` and adds `local_id` containing the stored ID. Each component is the original session/local ID encoded as UTF-8 percent escapes, with only ASCII letters, digits and `-._~` left literal; escapes use uppercase hex. No normalization of either original identifier is performed. Empty components, invalid UTF-8 escapes, noncanonical encodings, query/fragment suffixes and unsupported namespaces are rejected. The maximum is 1550 ASCII characters (13-character prefix, two components of at most 768 characters, and one separator). Example: session `session:one`, memory `memory/one` becomes `urn:claudiom:session%3Aone:memory%2Fone`.

On disk, memory definitions, supports and lifecycle references remain session-local. Public `supports` and `supersession.replacement_id` are qualified URNs, including in supporting observations. This is a pre-release reader-contract change; existing valid local-ID logs require no rewrite or migration.

### Generation

A `generation` event adds `coverage` and `memories` (an array, possibly empty). Coverage and all accepted memories form one commit. No per-memory partial acceptance in the writer: invalid input rejects the entire event.

Coverage has `lane` (`observation` or `reflection`), `input_id` and `from`/`to` nonnegative safe integers with `from < to`. Its identity is `(session_id, lane, input_id)`. Observation input IDs identify a source stream incarnation; the producer assigns a distinct ID to the parent and to each subagent stream, and changes it on a new incarnation. Progress units are byte offsets at complete-record boundaries. Reflection input IDs identify a stable ordered observation feed; progress units are feed positions. The producer must establish those identities and boundaries; the store does not infer them from Claude fields.

The first range starts at zero; subsequent ranges start at the prior committed `to`. Reject gaps, overlaps, regressions and a new event claiming previously covered work. Exact event replay is handled before coverage checks. Independent lanes and input IDs advance independently. Failed or cancelled generation produces no event.

Each memory has `id`, `kind`, `text`, and `source_time` (UTC RFC3339 or null when unavailable). Session and recording time are inherited from its generation event. An observation adds a nonempty `citations` array. A reflection adds a nonempty `supports` array of observation IDs, with no duplicates. An observation-lane commit accepts only observations; a reflection-lane commit accepts only reflections.

Each citation contains `citation_version` (currently 1, independently validated from the event version), `stream` (`parent` or `subagent`), `agent_id` (null for parent, nonempty for subagent), `path` (absolute source path), `source_id` (nonempty UUID/string or null), and `locator`. The locator has `input_id`, `start`, `end` and `sha256`: a nonempty half-open byte range and SHA-256 of its exact bytes. Native locators are required even when source IDs exist. Source IDs are metadata, not sufficient proof that a file still contains the cited bytes. The digest detects same-size in-place mutations that file identity/size checks alone miss. No transcript content is embedded in a citation. The path is a resolution hint, not immutable identity: Claude storage can move, and future source resolution may relocate it while verifying the native range/digest. Missing or unsupported citation versions are rejected; no implicit migration of earlier pre-release fixtures is performed. Stage 2 must characterize source timestamps (including fractional seconds), since Claude publishes no record schema.

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

For the same event ID, equality means identical parsed event values (object key order is irrelevant, array order is significant). Exact replay is a no-op. Any changed value is a conflict. An existing memory ID may recur only with an identical first definition, including inherited session/recording time; otherwise reject. No later definition overwrites the first. Fold validation is all-or-nothing within each session: corruption yields no partial success or coverage result from that log. Reader scans may return other healthy sessions with warnings. Writers require a fully validated target-session log, not a store-wide log snapshot. URNs establish global identity from the session namespace without reading unrelated definitions.

## Append, acknowledgement and repair

The writer interface requires one writer across the store (and therefore per session). It does not introduce a worker or lock/lease subsystem in this slice; callers must serialize access. Store-wide serialization remains necessary for the session-count limit checked against directory names before writing; concurrent new-session writers could otherwise race this check. Memory-ID validation is session-local. Append reads only its target log and scans directory names for the cap; a corrupt or unsafe unrelated session log/directory does not block target-session progress. Invalid shared roots or malformed session-directory names still fail the operation. Reads during an append may report an incomplete tail and retry; they must not repair or mutate it. Concurrent-writer support requires the serialized worker increment, not a claim that an append syscall makes whole transactions safe.

Write the complete frame with a loop handling short writes, then fsync the file. For newly created entries, fsync containing directories so acknowledged storage survives restart. Acknowledge only after these operations succeed. In a write/fsync error, report an uncertain outcome and require recovery; never optimistically update coverage. Re-read the log on retry. A crash after a durable frame but before acknowledgement is resolved by event replay without another logical generation.

`append!` refuses every unrepaired tail, including on identical replay, without truncating or writing. A separate `repair!` operation / `repair --session <id>` command performs its own read/process/write cycle: validate the complete prefix, compute the last LF boundary, verify file identity/size, truncate the unterminated tail, then fsync. It never appends events or changes recovered coverage. Repair shares the store-wide serialization requirement. A retry also fsyncs an already-repaired existing file, because a previous truncate may have succeeded before fsync/acknowledgement failed. Repair of a missing log is a no-op and creates no storage. Never repair a malformed complete frame or skip corruption. A complete valid frame found after an uncertain write is authoritative during recovery; a missing/incomplete frame is not. This does not prevent a provider call being repeated after a crash before commit.

## CLI reader

- `list [--session <id>]`: JSON array, active memories sorted by `(recorded_at, id)` ascending. Without a session filter, scan up to the session limit; do not create storage. A missing store is an empty list.
- `recall <urn> [--session <id>]` routes directly to the encoded session and reads only that log. An optional filter must match the URN's decoded session; otherwise exit 2. `recall <local-id> --session <id>` is a scoped shorthand (a `urn:claudiom:` prefix is interpreted as a URN); bare unscoped IDs are rejected rather than searched or guessed. Return a JSON object with the first stored definition, lifecycle status, correction links and provenance. For reflections, include supporting stored observations and their lifecycle statuses. Missing memory exits 1 after checking the target's committed definitions; only target-session corruption prevents establishing absence and causes exit 2 with `Memory unavailable; results incomplete.` Repair-needed warnings do not change a missing-memory exit code: unterminated tails are uncommitted and cannot contain stored definitions. Invalid arguments, unsafe store roots and store-wide scan-limit violations also exit 2.
- `repair --session <id>`: explicit write operation returning `{repaired, removed_bytes}` as JSON. Corrupt complete frames or unsafe storage exit 2 and are never repaired. This command is not retrieval.
- List/recall remain read-only, work without credentials/models/source transcripts, and preserve their JSON stdout shapes. On an incomplete tail, return the committed prefix and write `Repair needed for session <hash>; results incomplete.` to stderr; no implicit repair occurs.
- On an unreadable/corrupt session, skip its entire log and write `Corrupt session <hash>; results incomplete.` to stderr. Per-session unsafe entries, including symlinked or wrongly permissioned session directories/logs, are classified under this same `Corrupt session` warning and skipped, not treated as an invocation-wide exit 2 (also with `--session`). Unsafe store roots still fail the invocation with exit 2. The hash identifies the storage session even when its original ID cannot be recovered safely. Return healthy results (exit 0) while making incompleteness explicit. Unfiltered listing still scans healthy sessions and reports skipped sessions. Direct URN recall is unaffected by unrelated corrupt/torn logs and emits no warnings about sessions it never reads. Equal local IDs in different sessions are not conflicts; their URNs differ.
- Native consumers use `retrieve` to obtain `{result, warnings, incomplete}`. `incomplete` is true only for corrupt-session warnings; repair-needed warnings remain visible but do not make the committed-definition scan incomplete. CLI warnings contain only fixed text and validated session hashes, never private paths/transcript content. Root/scan errors are not swallowed as partial success.
- Citation expansion is deferred to the source-reader increment. Return `source_status: "not_checked"` alongside stored citations; do not claim a file is missing or resolved without inspecting it. Stored memory remains accessible regardless of source availability.

## Required evidence

Issue 002 lists the interface/restart/safety acceptance cases. Parameterized tests of actual private temporary files and packaged readers, plus deterministic generated fold/replay invariants, are in `test/cljs/claudiom/store_test.cljs` and `test/cli.test.js`. No host transcripts, real providers, hooks or background processes. This document specifies behavior, not proof that the implementation already provides it.
