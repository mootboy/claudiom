# claudiom

Independent observational memory for Claude Code, implemented in ClojureScript.

**Bootstrap only, version 0.0.0.** No generation, transcript scanning, hooks, persistence or recall is implemented yet. The target is [0.1.0](docs/plan-0.1.0.md).

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

## Acknowledgments

Claudiom is inspired by [pi-observational-memory](https://www.npmjs.com/package/pi-observational-memory), especially its use of observations, reflections and source-grounded recall. It is a standalone implementation for Claude Code and does not depend on pi-observational-memory at runtime.

## Distribution

Licensed [GPL-3.0-only](LICENSE). The package remains private (no npm publication); GitHub visibility and remote setup are still undecided. CI is prepared for Node 22.19/24; release automation validates a manually approved post-merge version tag and publishes a tested tarball/checksum, not npm. Nothing auto-publishes from this bootstrap.
