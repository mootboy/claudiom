# Local issue tracker

Sequential Markdown issues; states: proposed, ready, in-progress, done. Done requires test/evidence links, not just code. Use one small vertical slice per PR; user merges without squash.

- [001 — Initial 0.1.0](001-initial-release.md) — proposed, tracked by the staged plan.
- [002 — Native persistence contract and reader](002-native-persistence-and-reader.md) — done, implementation and evidence-linked criteria accepted by the user.
- [003 — Claude source reader and context selection](003-claude-source-reader-and-context-selection.md) — done, source/selection/verification and prefix amendment accepted by the user after re-review; Linus merges without squash. No repository probe slice.
- [004 — Store amendment, lock, registry and hook registration](004-store-lock-registry-and-hooks.md) — proposed, plan only; replaces the superseded provider-adapter proposal.
- [005 — In-session memory writer](005-in-session-memory-writer.md) — proposed, MCP producer/Haiku agent/nudge/remember plan only.
- [006 — Reflections and consolidation](006-reflections-and-consolidation.md) — proposed, exact-support implied archival plan only.
- [007 — MCP reads, setup, packaging and release](007-mcp-reads-setup-packaging-and-release.md) — proposed, implementation and release approvals remain separate.

The in-session redesign is user-approved; these issues are not implementation authorization. The superseded untracked provider-adapter draft was deleted with the user's approval; it is not part of the plan-revision PR.
