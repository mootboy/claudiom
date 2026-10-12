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

## Open: requires the manual checks

1. **Append vs rewrite** across `/compact`, `--resume` / `--continue`, `/clear` and fork. Finished files can't prove that earlier bytes were never rewritten. Hash a live transcript's prefix before and after each action.
2. **Hook payload spelling.** Check whether `transcript_path` / `agent_transcript_path` are absolute or `~/`-prefixed, by dumping stdin from a temporary local hook.
3. **The `attachment` record type** is 38% of parent records. It is distinct from the image/attachment *content blocks* the user decided to omit. Decide whether any `attachment` records belong in selection.
   - Observed `attachment.type` values are mostly harness-injected context: `total_tokens_reminder`, `batching_reminder_sent`, `deferred_tools_*`, `environment`, `instructions`, `skill_listing`, `session_context`, `mcp_instructions_delta`, `model`, `date`, `hook_success`, `credential_org`, `diagnostics`, `auto_mode`, `task_reminder`, `nested_memory`, `compact_file_reference`, `thinking_*`, and others.
   - User-originated values: `queued_command` (prompts typed while Claude was busy), `edited_text_file`, `file`, `prompt_snapshot`.
   - Recommendation: allowlist `queued_command` only, as user input. Exclude the rest. `credential_org` and `environment` must never reach a provider.
