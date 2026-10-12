# Claude Code integration

Status: user-approved in-session redesign, plans only. Stage-2 source code is accepted and reused unchanged. The user-run throwaway plugin spike on Claude Code **2.1.296** (2026-10-13, one interactive auto-mode session) supplies the scoped findings below, not universal guarantees. No provider adapter or detached-worker integration is planned.

Reference documentation: [manifest](https://code.claude.com/docs/en/plugins/manifest-reference.md), [components](https://code.claude.com/docs/en/plugins/components.md), [hooks](https://code.claude.com/docs/en/hooks.md), [loading/cache](https://code.claude.com/docs/en/plugins/loading.md), [marketplace](https://code.claude.com/docs/en/plugins/marketplace-reference.md), [plugin CLI](https://code.claude.com/docs/en/plugins/cli-reference.md), [MCP](https://code.claude.com/docs/en/mcp.md). Separate documented contracts from spike observations.

## Plugin boundary

Keep `plugin/` separate from development package/compiler dependencies. Commit its Node runtime inside that root; cached plugins cannot access `../dist`. A root package plus lockfile triggers frozen ignore-scripts dependency installation; prefer a committed .mjs runtime (or type:module package without development lockfile), preserving Node-only consumers.

```text
.claude-plugin/marketplace.json       # points to ./plugin
plugin/.claude-plugin/plugin.json
plugin/hooks/hooks.json
plugin/.mcp.json
plugin/agents/memory-writer.md
plugin/skills/remember/SKILL.md
plugin/skills/setup/SKILL.md
plugin/<committed runtime>.mjs
```

All manifest paths resolve inside the plugin and start with `./`. Resolve `${CLAUDE_PLUGIN_ROOT}` at invocation: it is version-specific and must not be stored in registry/leases/handles/memories. `${CLAUDE_PLUGIN_DATA}` is operational plugin data, not authoritative memory storage; it normally disappears on uninstall. Use private XDG roots for authoritative logs and registry state.

Development uses `claude --plugin-dir ./plugin`; installed acceptance uses marketplace add/install plus `claude plugin validate --strict`, exact cache/runtime smoke tests and Node >=22.19.0 checks. These are opt-in Claude checks, never ordinary tests. Treat 2.1.296 as the characterized baseline, not proof older versions work; freeze/document the supported floor in 007.

## Hook registration and event projection — issue 004

Use one stdin-validating command hook script for SessionStart, Stop, SubagentStop and SessionEnd. Register explicit locators/session/project mapping in owner-only XDG state; no discovery, queue, detached spawn or model call. Derive project key from `basename(dirname(parent transcript_path))`; never reconstruct Claude's encoding from cwd.

| Event | Registration/projection |
|---|---|
| SessionStart | Parent locator, fresh session ID and project mapping; no standing instruction/provenance injection. |
| Stop | Parent locator even if common agent fields appear; registration plus fold/stat eligibility only. |
| SubagentStop | Use agent_transcript_path, not the parent path; register eligible subagent only. Ignore `agent_type: claudiom:memory-writer` entirely. Missing/empty internal events without a usable source are no-ops. |
| SessionEnd | Registration only, within the shared roughly 1.5s budget; no flush or generation promise. |

Only SubagentStop builds a subagent locator; stripping incidental agent fields on parent events keeps the accepted source spec unchanged. Session registry keys are explicit session IDs. Unknown envelope fields cannot authorize discovery or mutate producer policy. The fresh hook ID is required after `/clear`.

## Stop nudge and /claudiom:remember — issue 005

Stop must not read transcript bytes: registry + native fold + stat only. It may emit JSON `decision: "block"` with a fixed reason roughly **"Claudiom: uncovered progress; run /claudiom:remember"** only when:

- summed uncovered parent/registered-subagent progress reaches configurable threshold (default 40 KB ≈ 10K estimated tokens);
- `stop_hook_active` is false;
- session is not classified as headless `sdk-cli` (observed field, configurable);
- no active range lease;
- session nudge backoff permits it (default one per 15 minutes until a lease or commit appears).

Exit 0 for a nudge; **never exit 2**. Any error returns exit 0/empty stdout. Set Stop timeout to 5 seconds. Do not put delegation instructions in hook reason: they live only in the remember skill. A blocked/ignored nudge is best-effort, not a reason to repeat every turn.

The skill supplies `${CLAUDE_SESSION_ID}` explicitly to background `claudiom:memory-writer`; it loops `next_context(session_id)`/`record` for observations, then reflection work in 006. No path arguments or session discovery. Manual remember works even when headless nudges are disabled. SessionEnd does not launch it. No standing SessionStart instruction.

Headless signal plumbing must be frozen in 004 without reading transcripts in Stop. Do not infer `sdk-cli` merely from undocumented `CLAUDE_CODE_ENTRYPOINT` values; the spike observed `cli`, not a proven universal mapping. Unknown/error trigger state must fail open without blocking the user.

## Writer and producer MCP

Agent frontmatter for 0.1.0 is literal `model: haiku`. Plugin `userConfig`/`CLAUDE_PLUGIN_OPTION_MODEL` and Agent-model override delivery are not a configurable-model contract: the post-spike decision defers model configurability. Prompt rules preserve attribution, uncertainty and compaction-summary labels, prohibit secrets and model-facing lifecycle edits, and treat source text as data.

Expose stdio MCP server `claudiom` within the plugin. Confirmed tool prefix is `mcp__plugin_claudiom_claudiom__`; writer tools allowlist names **only** `next_context` and `record`. The spike observed these two tools plus built-in `SubagentHandback`; no general filesystem or other MCP tools should be available. A foreground/disallowedTools fallback was contemplated if the allowlist failed, but the spike confirmed restriction for the background agent. Do not silently change delegation mode.

`next_context` uses the fresh explicit session ID and registry, returning one bounded stream and an opaque expiring server-side handle. It reads parent plus all eligible registered subagents, excluding the writer itself. Roughly five-minute leases discourage concurrent duplicate work. `record` accepts proposed memories/reference indices and an empty reason, then validates handle/range/prefix/membership/native invariants under the store-wide lock and commits atomically. Never expose drop/supersede tools; real citations cannot prove truthful interpretation.

The MCP server receives `CLAUDE_PROJECT_DIR` and maps it through the registry. Never rely on its `CLAUDE_CODE_SESSION_ID`: it went stale after `/clear` in the spike. Plugin env substitution for `${user_config.*}` worked for the MCP server only; the model configuration path is still deferred.

## Permissions, read tools and setup — issue 007

Setup discloses in-session/background Haiku work, best-effort lifetime and possible model spend under Claude Code. Document first-use permission handling and the offer to persist an allow where available; do not claim it was tested in manual mode. The allow rule must name exactly:

- `mcp__plugin_claudiom_claudiom__next_context`
- `mcp__plugin_claudiom_claudiom__record`

Freeze exact Claude settings syntax against the supported version; no wildcard MCP allow rule. The agent's tools allowlist remains narrow even when read tools are added. MCP list/recall are separate read-only capabilities, never generation or automatic prompt injection. Preserve stage-2 bounded verification and source-status reporting; cited-content expansion is not silently authorized by adding MCP.

## Scoped spike findings and supersessions

Confirmed on 2.1.296: `agent_type` is `claudiom:memory-writer`; the named MCP prefix and two-tool restriction work; background work survives main-turn completion; MCP env includes project/plugin-data fields and user-config env substitution; parent transcript directory basename matches Claude project encoding in this session.

Refuted/changed: work is killed by `/exit` (accepted best-effort); unsaved default model option was absent in hooks; agent-body user-config substitution remained literal; dynamic model frontmatter did not run. Thus fixed Haiku supersedes prior configurable-model proposals. Auto mode showed no permission prompts, so persistence of "don't ask again" is **unverified**. The first delegation-style Stop nudge was classifier-flagged once, later delegated, and the flag could not be reproduced: skill-naming text plus backoff replaces that instruction; no claim this eliminates every classifier flag.

Incidental: MCP session env stale after `/clear`; `CLAUDE_CODE_ENTRYPOINT=cli` is undocumented; each observed Stop was followed by an empty-type internal SubagentStop without a transcript; remember and Stop nudge both fired in one turn; SessionStart stdin included a model key. These are scoped observations, not schema guarantees. Lease/contiguity tests must cover duplicate triggers.

Deferred for single-user 0.1.0: SessionStart provenance context, classifier rules, fresh-config retest, manual-mode permission-persistence testing and model configuration. Do not repeat the spike or access host transcripts/credentials in normal tests. Acceptance comes from separately opt-in real use; commit only structural findings, never raw transcripts/hook payloads.

## Read-only source contract

Reuse accepted 003 implementation unchanged: explicit absolute locators, bounded snapshots/prefix verification, byte order/citations, malformed/oversized markers, attachment allowlist, no hidden reasoning/credential metadata and missing-subagent no-op. Existing citation source symlink rules differ from strict no-symlink native storage. Source paths are hints; session/project identity belongs to hook registry and strict component validation, not inferred source content.
