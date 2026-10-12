# claudiom

Independent observational memory for Claude Code, implemented in ClojureScript.

**Pre-release, version 0.0.0.** Native persistence, bounded explicit-source reading/selection and read-only CLI listing/recall with citation verification are implemented. Generation, transcript discovery, hooks and MCP are not. The target is [0.1.0](docs/plan-0.1.0.md).

## Development

Node >=22.19.0, npm and Java 21+ are build prerequisites. Packaged consumers require only Node.

```sh
npm ci --ignore-scripts
npm test
node cli.js status
npm run ci
```

`npm run watch` writes development output under `target/dev`, not `dist`; run it with `npm run dev -- status` in another terminal. See [DEVELOPMENT.md](DEVELOPMENT.md).

## Boundaries

Claude transcripts are read-only input. Claudiom owns generation, native persistence, listing and recall, with no external memory service or agent harness dependency.

An **observation** retains a source-grounded decision or finding. A **reflection** synthesizes committed observations into higher-level understanding, with traceable support. See the planned [memory model](docs/memory-model.md).

See [architecture](docs/architecture.md), [release plan](docs/plan-0.1.0.md) and [issues](docs/issues/README.md).

## Native retrieval

```sh
node cli.js list [--session <id>]
node cli.js recall 'urn:claudiom:<encoded-session>:<encoded-memory>'
node cli.js recall <local-id> --session <id>
```

These commands return JSON from private native logs under XDG data storage. Public memory IDs are session-qualified URNs; recall reads only the addressed session. They never call models, create storage or modify transcripts. Corrupt sessions are skipped with identifying session-hash warnings on stderr; incomplete tails also produce repair-needed warnings. Results may be incomplete. List performs no source I/O (`source_status: "not_checked"`). Recall hashes bounded cited ranges, including reflection supports, and reports verified/changed/missing/unreadable/verification_limited without returning source content.

Use `node cli.js repair --session <id>` explicitly to truncate an incomplete native-log tail; writers refuse to append until it is repaired. Repair never modifies Claude transcripts. No generation/commit command exists yet. See the [native persistence contract](docs/native-persistence.md) for format, limits and single-writer requirements.

## Acknowledgments

Claudiom is inspired by [pi-observational-memory](https://www.npmjs.com/package/pi-observational-memory), especially its use of observations, reflections and source-grounded recall. It is a standalone implementation for Claude Code and does not depend on pi-observational-memory at runtime.

## Distribution

Licensed [GPL-3.0-only](LICENSE). The package remains private (no npm publication); GitHub visibility and remote setup are still undecided. CI is prepared for Node 22.19/24; release automation validates a manually approved post-merge version tag and publishes a tested tarball/checksum, not npm. Nothing auto-publishes from this bootstrap.
