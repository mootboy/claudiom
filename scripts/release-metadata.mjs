import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export function validateRelease(manifest, lockfile, changelog, tag) {
  const version = manifest.version;
  if (!/^\d+\.\d+\.\d+$/.test(version) || version === "0.0.0") throw new Error("Not a release version");
  if (tag !== `v${version}`) throw new Error("Tag/package version mismatch");
  if (lockfile.version !== version || lockfile.packages?.[""]?.version !== version)
    throw new Error("Lockfile/package version mismatch");
  if (!changelog.split("\n").some(line =>
    line.startsWith(`## [${version}] - `) && /^\d{4}-\d{2}-\d{2}$/.test(line.slice(-10))))
    throw new Error("Missing dated changelog release heading");
  return version;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  validateRelease(JSON.parse(readFileSync("package.json", "utf8")),
    JSON.parse(readFileSync("package-lock.json", "utf8")),
    readFileSync("CHANGELOG.md", "utf8"), process.env.RELEASE_TAG);
}
