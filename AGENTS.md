# Agent guidance

- Read `docs/architecture.md` and `docs/plan-0.1.0.md` before implementation.
- Application behavior is ClojureScript; JavaScript files are minimal Node/package boundaries.
- Run `npm test` after changes. Commit clean release output in `dist/claudiom.js`.
- Watch builds go to `target/dev`, never committed `dist`.
- Use `npm ci --ignore-scripts`; normal tests must use fake providers, synthetic transcripts and private temporary state.
- No real model calls, host credentials, transcript directories, hooks or background workers in normal tests.
- Claudiom independently generates, persists, lists and recalls memory. Never require Adam's runtime, schema, identity, spool or installation.
- Treat source transcripts as read-only. Preserve crash-safe coverage and owner-only storage; never advance coverage on failure/cancellation.
- Keep changes reviewable and limited to the current vertical slice.
- Leave PR merges to the user. Preserve history; do not squash.
- Version changes go through a PR. Tag only merged, validated main with explicit approval; no npm lifecycle pushes.
