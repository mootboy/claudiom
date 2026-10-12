import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = fileURLToPath(new URL("../", import.meta.url));
const manifest = JSON.parse(readFileSync(path.join(repository, "package.json"), "utf8"));
const invoke = (cli, args, cwd, env) =>
  execFileSync(process.execPath, [cli, ...args], { cwd, env, encoding: "utf8", timeout: 15000 });

test("compiled CLI disables generation and rejects unknown commands", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-cli-"));
  const env = { PATH: "", HOME: root, XDG_CONFIG_HOME: root, XDG_DATA_HOME: root, XDG_STATE_HOME: root };
  try {
    const cli = path.join(repository, "cli.js");
    const result = JSON.parse(invoke(cli, ["status"], root, env));
    assert.equal(result.version, manifest.version);
    assert.equal(result.enabled, false);
    assert.equal(result.implemented, true);
    assert.match(invoke(cli, ["--help"], root, env), /Generation disabled/);
    assert.equal(spawnSync(process.execPath, [cli, "unknown"], { cwd: root, env }).status, 1);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("packaged reader retrieves synthetic native memories across processes", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-reader-"));
  const env = { PATH: "", HOME: root, XDG_DATA_HOME: root };
  const cli = path.join(repository, "cli.js");
  try {
    const session = "synthetic";
    const dir = path.join(root, "claudiom", "v1", "sessions", createHash("sha256").update(session).digest("hex"));
    // All application-owned ancestors must be private.
    for (const suffix of ["claudiom", "claudiom/v1", "claudiom/v1/sessions", path.relative(root, dir)]) {
      mkdirSync(path.join(root, suffix), { mode: 0o700 });
    }
    const event = { version: 1, event_id: "fixture-1", session_id: session,
      recorded_at: "2026-10-12T12:00:00Z", kind: "generation",
      coverage: { lane: "observation", input_id: "parent-1", from: 0, to: 100 },
      memories: [{ id: "observation-1", kind: "observation", text: "Synthetic decision.", source_time: null,
        citations: [{ stream: "parent", agent_id: null, source_id: null, path: "/synthetic/unavailable.jsonl",
          locator: { input_id: "parent-1", start: 0, end: 100, sha256: "a".repeat(64) } }] }] };
    writeFileSync(path.join(dir, "events.jsonl"), JSON.stringify(event) + "\n", { mode: 0o600 });
    assert.equal(JSON.parse(invoke(cli, ["list"], root, env))[0].id, "observation-1");
    const recalled = JSON.parse(invoke(cli, ["recall", "observation-1", "--session", session], root, env));
    assert.equal(recalled.text, "Synthetic decision.");
    assert.equal(recalled.citations[0].source_status, "not_checked");
    assert.equal(spawnSync(process.execPath, [cli, "recall", "missing"], { cwd: root, env }).status, 1);
    assert.equal(spawnSync(process.execPath, [cli, "list", "--bad"], { cwd: root, env }).status, 2);
    writeFileSync(path.join(dir, "events.jsonl"), "private-invalid-content\n");
    const bad = spawnSync(process.execPath, [cli, "list"], { cwd: root, env, encoding: "utf8" });
    assert.equal(bad.status, 2);
    assert.equal(bad.stdout, "");
    assert.ok(!bad.stderr.includes("private-invalid-content"));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("exact tarball runs without compiler, JVM or model credentials", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-package-"));
  try {
    const packed = JSON.parse(execFileSync("npm", ["pack", "--json", "--pack-destination", root],
      { cwd: repository, encoding: "utf8" }))[0];
    const tarball = process.env.CLAUDIOM_PACKAGE_TARBALL || path.join(root, packed.filename);
    const consumer = path.join(root, "consumer");
    mkdirSync(consumer);
    execFileSync("tar", ["-xzf", tarball, "-C", consumer]);
    const pkg = path.join(consumer, "package");
    const env = { PATH: "", HOME: root, XDG_CONFIG_HOME: root, XDG_DATA_HOME: root, XDG_STATE_HOME: root };
    const result = JSON.parse(invoke(path.join(pkg, "cli.js"), ["status"], consumer, env));
    assert.equal(result.version, manifest.version);
    assert.equal(result.enabled, false);
    assert.deepEqual(JSON.parse(invoke(path.join(pkg, "cli.js"), ["list"], consumer, env)), []);
    assert.ok(packed.files.some(file => file.path === "dist/claudiom.js"));
    assert.ok(packed.files.some(file => file.path === "LICENSE"));
    assert.equal(JSON.parse(readFileSync(path.join(pkg, "package.json"), "utf8")).license, "GPL-3.0-only");
    assert.match(readFileSync(path.join(pkg, "LICENSE"), "utf8"), /GNU GENERAL PUBLIC LICENSE/);
    assert.ok(!packed.files.some(file => file.path.startsWith("src/")));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
