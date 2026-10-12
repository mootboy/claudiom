import assert from "node:assert/strict";
import test from "node:test";
import { validateRelease } from "../scripts/release-metadata.mjs";

test("release rejects bootstrap, wrong tags, stale lockfiles and missing changelog", () => {
  const pkg = { version: "0.1.0" };
  const lock = { version: "0.1.0", packages: { "": { version: "0.1.0" } } };
  const notes = "## [0.1.0] - 2026-10-12";
  assert.equal(validateRelease(pkg, lock, notes, "v0.1.0"), "0.1.0");
  assert.throws(() => validateRelease({version:"0.0.0"}, lock, notes, "v0.0.0"));
  assert.throws(() => validateRelease(pkg, lock, notes, "v0.2.0"));
  assert.throws(() => validateRelease(pkg, {...lock, version:"0.0.0"}, notes, "v0.1.0"));
  assert.throws(() => validateRelease(pkg, lock, "## [Unreleased]", "v0.1.0"));
});
