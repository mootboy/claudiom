# Claude transcript structure (observed)

Status: findings from a one-off, read-only structural scan of one user's local transcripts on 2026-10-12. These are observations, not a documented contract. Claude Code publishes no transcript schema. Treat every field as optional and unstable, and parse defensively.

## Scope

- 292 files under the projects directory (77 MiB): 145 parent transcripts and 43 subagent transcripts. The other 104 are sidecar `.json`, `.md` and `.txt` files.
- Claude Code versions recorded in records: 2.1.251 – 2.1.296.
- Entrypoints: `cli`, `sdk-cli`, `claude-desktop`.
- Output was counts, schema key names and enum values only. No content, paths, IDs, cwd or timestamps were inspected or recorded.

## Layout

| Path class | Count |
|---|---|
| `<project>/<session-id>.jsonl` (parent) | 145 |
| `<project>/<session-id>/subagents/agent-<agentId>.jsonl` | 43 |
| `<project>/<session-id>/subagents/<file>.json` (one sidecar per subagent) | 43 |
| `<project>/<session-id>/tool-results/<file>.txt` | 24 |
| `<project>/<session-id>/<file>.md` | 36 |

- Every parent file has exactly one `sessionId`, equal to its filename.
- Every subagent file has one `agentId`, its filename is `agent-<agentId>.jsonl`, and its `sessionId` equals the *parent* session.
- Some large tool outputs are stored outside the transcript in `tool-results/`. Their records are not self-contained; whether to follow these files is a selection-policy decision (not yet decided).

## Framing

- One JSON object per LF-terminated line. Across the whole tree: no BOM, no CR, no file missing its trailing LF, no malformed lines.
- 17 lines exceed 1 MiB (largest about 1.3 MiB), mostly tool results and prompts carrying base64 images.

## Record types

`type` is always present. Counts are parent / subagent:

- **Conversation records** (`parentUuid`, `uuid`, `isSidechain`, `timestamp`, `cwd`, `sessionId`, `version`, `gitBranch`, `userType`, `entrypoint`):
  - `user` 6201 / 1125
  - `assistant` 10275 / 1790
  - `attachment` 11254 / 488
  - `system` 1414 / 0
- **Metadata records**, mostly without `uuid` or `timestamp`: `last-prompt`, `atis-latch`, `mode`, `ai-title`, `permission-mode`, `pr-link`, `file-history-snapshot`, `file-history-delta`, `queue-operation`, `frame-link`, `cost-state`, `custom-title`, `relocated` (with `relocatedCwd`), `artifact-*`.

Selection should allowlist `user` and `assistant`, plus policy-chosen `attachment` and `system` subtypes. Everything else is an unknown or metadata type: omit it with a marker and advance.

## Fields

- `isSidechain`: `false` in every parent record, `true` in every subagent record.
- `agentId`: present on subagent records only.
- `timestamp`: always `YYYY-MM-DDTHH:MM:SS.mmmZ` (milliseconds, UTC). This is compatible with the native RFC3339 spec.
  - **Not monotonic** in 139 of 188 transcripts. Order by byte offset, never by timestamp.
- `cwd`: changes within 15 of 145 parent files. Keep it per record (as decided).
- `uuid` / `parentUuid`: 32,335 parent links resolve within the same file, 195 are `null` (starts and compaction boundaries) and 17 point outside the file.
  - No `uuid` appears in more than one file, so resume or fork did not copy records into new files in this data.
- `message.id`: assistant API messages are split across lines, one content block per line. 3,994 message IDs span 10,049 lines. Treat each line as its own byte-located record (as decided). Don't merge them.
- `session_id` (snake_case) appears alongside `sessionId` on some records. Its origin is unknown; don't rely on it.

## Content blocks

- `assistant`: `tool_use` 6101, `thinking` 3958 (exclude), `text` 2008.
- `user`: `tool_result` 6085, plain string content 1213, `text` 30, `image` 2.
- Inside `tool_result`: `text` 1524, `tool_reference` 550, `image` 21.

## Compaction

- 7 compactions were observed. Each appends, within the same file:
  - a `system` record with `subtype: compact_boundary`, `logicalParentUuid` and `compactMetadata`;
  - a `user` record with `isCompactSummary: true`.
- No evidence of rewriting: compaction appears as appended records. The summary is model-generated text, not source evidence. Recommendation: omit it from selection, or mark it as a summary.

## `system` subtypes

`turn_duration` 894, `stop_hook_summary` 492, `informational` 14, `compact_boundary` 7, `local_command` 5, `scheduled_task_fire` 2.

## Manual checks

- **`/clear` (user-observed, 2026-10-12):** creates a new session file, i.e. a new session ID. The old session is a separate stream with its own native log, so there is no incarnation impact. This is consistent with the documented `SessionEnd` `reason: clear` for the old session and `SessionStart` `source: clear` for the new one. Whether the old file's bytes stay untouched was not separately hashed.

- **`/compact`, `--resume` and fork (user-observed, 2026-10-12):** the SHA-256 of the transcript's prior bytes was unchanged after each action. Existing bytes are preserved, i.e. append-only; no rewrite was observed. This satisfies the D6 gate: the prefix-digest incarnation rule (D1) can be frozen, and it stays as a guard against unobserved rewrites. Whether `--resume` or fork continue the same file or start a new one was not recorded. Either way, prefix verification handles it.

- **Hook payloads (user-captured `Stop` and `SubagentStop` stdin, 2026-10-12; only field names and structure inspected):**
  - `transcript_path` and `agent_transcript_path` are absolute paths, with no `~/` prefix. The locator spec can require absolute paths; no `HOME` expansion is needed.
  - `transcript_path` = `<project-dir>/<session_id>.jsonl`, and `agent_transcript_path` = `<project-dir>/<session_id>/subagents/agent-<agent_id>.jsonl`, matching the scanned layout. `agent_id` was 17 lowercase hex characters.
  - Fields beyond the documented set were present: `scratchpad_dir`, `prompt_id`, `effort`. The locator spec must tolerate extra fields (as decided).
  - The captured `SubagentStop` had an empty `agent_type`, and **its `agent_transcript_path` did not exist** when checked; the parent file did. Either internal agents fire `SubagentStop` without writing a transcript, or the file is written later or removed. The dump kept only the last event, so this is one observation. Requirement: a missing subagent source is a normal no-op outcome, with no error, no coverage and no retry loop. The stage 4 hook policy decides whether to enqueue empty-`agent_type` events.

## Attachment record policy (user decision, 2026-10-12)

`attachment` records are 38% of parent records and are mostly context injected by Claude Code. Selection uses an **allowlist on `attachment.type`**. Every other attachment type, including future ones, is omitted with a marker and coverage advances past it.

- **`queued_command`:** include only when `commandMode == "prompt"` and `origin.kind == "human"`. Present it as a user message using `prompt`. Key on `origin.kind`, not `humanTurn`: 15 of the 29 human prompts lack `humanTurn`.
  - Exclude `commandMode: task-notification` (44 observed, background task completions).
  - Exclude `origin.kind: peer` (1 observed, from another session; untrusted).
- **`file`, `edited_text_file`:** emit a filename-only marker, e.g. `[user referenced file <name>]` / `[file edited: <name>]`. Use `displayPath` when present, otherwise `filename`. Never include `content` or `snippet`.
- **Everything else is excluded:** reminders and bookkeeping, `instructions`/`nested_memory`/`prompt_snapshot`, and hook output (`hook_success`, `hook_additional_context`).
  - `environment`, `session_context` and `credential_org` must never reach a provider.
