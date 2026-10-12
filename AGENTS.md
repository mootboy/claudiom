# Agent guidance

- Read `docs/architecture.md`, `docs/memory-model.md` and `docs/plan-0.1.0.md` before implementation.
- Read `docs/claude-integration.md` before touching plugin packaging, hooks, transcript reading or MCP.
- Application behavior is ClojureScript; JavaScript files are minimal Node/package boundaries.
- Run `npm test` after changes. Commit clean release output in `dist/claudiom.js`.
- Watch builds go to `target/dev`, never committed `dist`.
- Use `npm ci --ignore-scripts`; normal tests must use fake providers, synthetic transcripts and private temporary state.
- No real model calls, host credentials, transcript directories, hooks or background workers in normal tests.
- Claudiom owns generation, native persistence, listing and recall. Do not introduce external memory-service dependencies.
- Treat source transcripts as read-only. Preserve crash-safe coverage and owner-only storage; never advance coverage on failure/cancellation.
- Keep changes reviewable and limited to the current vertical slice.
- Leave PR merges to the user. Preserve history; do not squash.
- Version changes go through a PR. Tag only merged, validated main with explicit approval; no npm lifecycle pushes.

## Development principles
1. Reads up front, writes at the end. Keep the processing code pure, only read state at the beginning of a process and write it at the end.
2. Maintain locality of behaviour, prefer keeping related functions close to the code using it. Avoid creating new files/modules unless they are needed for shared functionality.
3. Focus testing on interfaces first, use parametrised tests. Use property based testing when applicable. Only introduce unit tests for functions that are hard to test through the interfaces.
4. Prefer clojure specs to types, hand-written validation or schema libraries.
