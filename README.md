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

Claude transcripts are read-only input. Claudiom owns its native memories and independent recall. Adam may later add its own reader; Claudiom does not know Adam exists. Pi Durable is not a dependency.

See [architecture](docs/architecture.md), [release plan](docs/plan-0.1.0.md) and [issues](docs/issues/README.md).

## Distribution

Private/unlicensed bootstrap. Licensing and GitHub repository setup require a separate decision before public release. CI is prepared for Node 22.19/24; release automation validates a manually approved post-merge version tag and publishes a tested tarball/checksum, not npm. Nothing auto-publishes from this bootstrap.
