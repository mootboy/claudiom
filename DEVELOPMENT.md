# Development and releases

## Local loop

1. `npm ci --ignore-scripts` (lockfile is authoritative).
2. Add one failing test, implement the smallest vertical slice, run `npm test`.
3. Use `npm run watch` in one terminal and `npm run dev -- status` in another; dev files live only in `target/dev`.
4. Before a PR: `npm run clean && npm test`, stage generated `dist/claudiom.js`, then `npm run ci` and `git diff --check`. Review generated changes before committing.
5. Normal tests are offline and use private synthetic state. Real Claude/provider probes must be explicitly opt-in, isolated and cost-bounded; that test path is not implemented yet.

No JVM/compiler is required to run the published CLI. The package smoke test extracts the exact tarball into an empty consumer and runs it with no PATH, model credentials or Adam.

## GitHub setup (not performed)

Create/configure the remote separately, enable PR protection and require both Node matrix checks. Disable squash merging. Workflow third-party actions use Adam's reviewed full-SHA pins. Dependabot ignores automatic major npm upgrades.

## Release procedure

Only the final 0.1.0 PR bumps package/lockfile versions and adds a dated changelog heading. Do not run `npm version` with Git tagging enabled. User merges with history preserved; wait for green main CI.

After explicit release approval, tag the tested merged main commit:

```sh
git fetch origin
git tag -a v0.1.0 origin/main -m "claudiom v0.1.0"
git push origin refs/tags/v0.1.0
```

The tag workflow revalidates version/lockfile/changelog/main ancestry, clean build drift and exact package, then publishes a draft with tarball and SHA-256 before making it public. It rejects bootstrap version 0.0.0 and an existing release. No direct main push, branch mutation, automatic npm publication, or pre-merge tag.

Manual post-merge tagging is deliberate for the initial release; no custom auto-tag service is needed yet.
