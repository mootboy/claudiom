# Claude Code integration

Status: guidance for the plugin, hook, transcript and MCP increments. Researched against code.claude.com on 2026-10-12. Items marked *inferred* are not stated by the docs and need probe evidence before they are relied on.

References: [manifest](https://code.claude.com/docs/en/plugins/manifest-reference.md), [components](https://code.claude.com/docs/en/plugins/components.md), [hooks](https://code.claude.com/docs/en/hooks.md), [loading/cache](https://code.claude.com/docs/en/plugins/loading.md), [marketplace](https://code.claude.com/docs/en/plugins/marketplace-reference.md), [plugin CLI](https://code.claude.com/docs/en/plugins/cli-reference.md), [MCP](https://code.claude.com/docs/en/mcp.md).

## Plugin packaging

Keep the plugin root separate from the repository root. Claude Code runs a frozen `--ignore-scripts` install inside the installed copy when the plugin root contains `package.json` plus a lockfile (`package-lock.json` v2/v3, `npm-shrinkwrap.json` or `bun.lock`). It also copies everything under the plugin root into its cache. The repository root would cause both: it would pull in development dependencies and copy `src/`, `test/` and `node_modules/`.

Proposed layout:

```text
.claude-plugin/marketplace.json   # repo is its own marketplace; plugin source "./plugin"
plugin/
  .claude-plugin/plugin.json      # only "name" is required
  hooks/hooks.json
  .mcp.json
  <committed runtime copy>        # .mjs, or a package.json with "type":"module" and no lockfile
```

- At install, plugin files are copied to `~/.claude/plugins/cache/<marketplace>/<plugin>/<version>/`. Files outside the plugin root are not copied, so `../dist` paths break. Copy the built runtime into `plugin/` and commit it there.
- `${CLAUDE_PLUGIN_ROOT}` changes every version, and old version directories are removed 14 days after they are orphaned. Never persist this path in the queue, leases or memory records. Resolve it again from the invoking environment each time.
- `${CLAUDE_PLUGIN_DATA}` (`~/.claude/plugins/data/<id>/`) survives updates but is deleted on uninstall unless the user passes `--keep-data`. Store authoritative memory and coverage under XDG paths, as [architecture.md](architecture.md) proposes, and never under the plugin data directory.
- Component paths in `plugin.json` must start with `./` and resolve inside the plugin root. Unknown top-level keys are stripped with a warning.
- Development: `claude --plugin-dir ./plugin` loads the plugin in place without copying. Users install with `claude plugin marketplace add <repo>` followed by `claude plugin install claudiom@<marketplace>`.
- `claude plugin validate --strict` is the authoritative manifest check. It needs Claude Code installed, so run it as an opt-in release check, never in normal tests.

## Hooks

Register `SessionStart`, `Stop`, `SubagentStop` and `SessionEnd` in `plugin/hooks/hooks.json`, which uses the settings hook shape under a top-level `"hooks"` key. Use the exec form so no shell is involved:

```json
{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "node",
  "args": ["${CLAUDE_PLUGIN_ROOT}/<runtime>", "hook"], "timeout": 5}]}]}}
```

Hook contract:

- Read stdin JSON. Validate it, append the locator to the durable inbox, start the worker without waiting for it, then `exit 0` with empty stdout.
- Never exit 2. On `Stop` it is a blocking error that keeps Claude running. Other non-zero codes are non-blocking errors. Claude does not see stderr when the exit code is 0.
- Hooks inherit `CLAUDE_PLUGIN_ROOT`, `CLAUDE_PLUGIN_DATA` and `CLAUDE_PROJECT_DIR`. On Linux and macOS, hook processes have no controlling terminal.

Worker launch:

- Do not use `"async": true` to start the worker. In `claude -p` mode, Claude Code cancels async hooks still running at teardown. The docs recommend a fully detached process for work that must outlive the session. In Node: `spawn(process.execPath, [...], {detached: true, stdio: 'ignore'}).unref()`.
- *Inferred:* the docs do not say whether a detached child survives an interactive session exit. Characterize this with the opt-in probe on Linux before relying on it.

Event specifics:

| Event | Relevant input (beyond `session_id`, `transcript_path`, `cwd`, `hook_event_name`) | Notes |
|---|---|---|
| `SessionStart` | `source`: `startup` \| `resume` \| `clear` \| `compact` \| `fork` | Runs on every session, so keep it fast. Handler types: `command` or `mcp_tool` only. `source` is the documented compaction and resume signal. |
| `Stop` | `stop_hook_active`, `last_assistant_message` | Does not fire when the user interrupts, so SessionStart repair must pick up that progress. |
| `SubagentStop` | `agent_id`, `agent_type`, `agent_transcript_path` | `transcript_path` is still the parent's. `agent_transcript_path` is the documented subagent locator. Some internal agents fire this event with an empty `agent_type`; decide explicitly whether to skip them. |
| `SessionEnd` | `reason`: `clear` \| `resume` \| `logout` \| `prompt_input_exit` \| `other` | All hooks share a default budget of about 1.5s. Plugin hook `timeout` values do not raise it. Enqueue only; the worker does any flush. |

## Transcripts

The docs publish no schema for transcript lines. They document only the locations: the parent `transcript_path` and `<session>/subagents/agent-<agentId>.jsonl` via `agent_transcript_path`.

- Treat transcripts as read-only, and treat every line field as unstable. Parse defensively, and report records that cannot be parsed instead of failing the stream.
- The [one-off structural scan and user-run manual checks](claude-transcripts.md) supply stage-2 evidence; no repository transcript probe is planned. `/clear` creates a new session/file; observed `/compact`, `--resume` and fork preserved prior bytes. These are scoped local observations, not universal documented behavior. Do not commit captured transcripts as fixtures.
- **Approved stage-2 contract, pending readiness/implementation:** hook paths are absolute, with no tilde expansion; tolerate extra stdin fields such as `scratchpad_dir`, `prompt_id` and `effort`. Read explicitly supplied subagents regardless of empty `agent_type`, but a missing subagent source is a normal no-op with no error, coverage or retry loop. Stage 4 separately decides enqueue eligibility.
- **Approved native locator contract:** paths are resolution hints; exact byte ranges/digests remain mandatory with or without UUIDs. File identity and size alone do not detect middle-prefix rewrites. Resume requires re-hashing the committed prefix; mismatch/truncation selects a fold-derived new incarnation and replay from zero, as frozen in [003](issues/003-claude-source-reader-and-context-selection.md) and the [native contract](native-persistence.md). Attachment records use 003's approved allowlist, never blanket inclusion of injected context.

## MCP recall

Declare one stdio server in `plugin/.mcp.json`:

```json
{"mcpServers": {"claudiom": {"command": "node",
  "args": ["${CLAUDE_PLUGIN_ROOT}/<runtime>", "mcp"]}}}
```

- `${CLAUDE_PLUGIN_ROOT}` and `${CLAUDE_PLUGIN_DATA}` are substituted and exported to stdio servers.
- The server appears in `/mcp` as `plugin:claudiom:claudiom`. Tools are exposed as `mcp__plugin_claudiom_claudiom__<tool>`; this naming comes from plugin tooling references, so confirm it in the probe.
- `node` resolves from the user's `PATH`. Check the `>=22.19.0` floor at startup in both hook and MCP entry points, and fail with a clear diagnostic.
- Tools are read-only list and recall operations. They make no model calls and inject nothing automatically.

## Claude Code version floor

The docs do not define one minimum Claude Code version for plugins. Individual features have their own floors, for example `onFailure` hook handlers (2.1.295), `claude plugin install --json` (2.1.268) and `CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS` (2.1.268). Choose the floor in the hooks increment from the version the probe ran on, and record it in the README.
