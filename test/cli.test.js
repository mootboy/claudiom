import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, appendFileSync } from "node:fs";
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

function installSyntheticFixture(root, session = "synthetic") {
  const dir = path.join(root, "claudiom", "v1", "sessions", createHash("sha256").update(session).digest("hex"));
  for (const suffix of ["claudiom", "claudiom/v1", "claudiom/v1/sessions", path.relative(root, dir)]) {
    mkdirSync(path.join(root, suffix), { mode: 0o700, recursive: true });
  }
  const observation = { version: 1, event_id: "fixture-1", session_id: session,
    recorded_at: "2026-10-12T12:00:00Z", kind: "generation",
    coverage: { prefix_sha256: "a".repeat(64), lane: "observation", input_id: "parent-1", from: 0, to: 100 },
    memories: [{ id: "observation-1", kind: "observation", text: "Synthetic decision.", source_time: null,
      citations: [{ citation_version: 1, stream: "parent", agent_id: null, source_id: null, path: "/synthetic/unavailable.jsonl",
        locator: { input_id: "parent-1", start: 0, end: 100, sha256: "a".repeat(64) } }] }] };
  const reflection = { ...observation, event_id: "fixture-2",
    coverage: { lane: "reflection", input_id: "feed-1", from: 0, to: 1 },
    memories: [{ id: "reflection-1", kind: "reflection", text: "Synthetic inference.", source_time: null,
      supports: ["observation-1"] }] };
  writeFileSync(path.join(dir, "events.jsonl"), [observation, reflection].map(JSON.stringify).join("\n") + "\n", { mode: 0o600 });
  return { session, dir };
}

test("packaged reader retrieves synthetic native memories across processes", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-reader-"));
  const env = { PATH: "", HOME: root, XDG_DATA_HOME: root };
  const cli = path.join(repository, "cli.js");
  try {
    const { session, dir } = installSyntheticFixture(root);
    assert.equal(JSON.parse(invoke(cli, ["list"], root, env))[0].id, "urn:claudiom:synthetic:observation-1");
    const recalled = JSON.parse(invoke(cli, ["recall", "urn:claudiom:synthetic:observation-1", "--session", session], root, env));
    assert.equal(recalled.text, "Synthetic decision.");
    assert.equal(recalled.citations[0].source_status, "not_checked");
    assert.equal(recalled.local_id, "observation-1");
    assert.equal(JSON.parse(invoke(cli, ["recall", "observation-1", "--session", session], root, env)).id, recalled.id);
    assert.equal(spawnSync(process.execPath, [cli, "recall", "observation-1"], { cwd: root, env }).status, 2);
    assert.equal(spawnSync(process.execPath, [cli, "recall", recalled.id, "--session", "other"], { cwd: root, env }).status, 2);
    assert.equal(spawnSync(process.execPath, [cli, "recall", "urn:claudiom:synthetic:missing"], { cwd: root, env }).status, 1);
    assert.equal(spawnSync(process.execPath, [cli, "list", "--bad"], { cwd: root, env }).status, 2);
    writeFileSync(path.join(dir, "events.jsonl"), "private-invalid-content\n");
    const bad = spawnSync(process.execPath, [cli, "list"], { cwd: root, env, encoding: "utf8" });
    assert.equal(bad.status, 0);
    assert.deepEqual(JSON.parse(bad.stdout), []);
    assert.match(bad.stderr, new RegExp(`Corrupt session ${path.basename(dir)}; results incomplete`));
    assert.ok(!bad.stderr.includes("private-invalid-content"));
    installSyntheticFixture(root, "healthy");
    const partial = spawnSync(process.execPath, [cli, "list"], { cwd: root, env, encoding: "utf8" });
    assert.equal(partial.status, 0);
    assert.equal(JSON.parse(partial.stdout)[0].session_id, "healthy");
    assert.ok(partial.stderr.includes(path.basename(dir)));
    const healthy = spawnSync(process.execPath, [cli, "recall", "urn:claudiom:healthy:reflection-1"], { cwd: root, env, encoding: "utf8" });
    assert.equal(healthy.status, 0);
    assert.equal(JSON.parse(healthy.stdout).session_id, "healthy");
    assert.equal(healthy.stderr, "");
    const unavailable = spawnSync(process.execPath, [cli, "recall", "urn:claudiom:synthetic:missing"], { cwd: root, env, encoding: "utf8" });
    assert.equal(unavailable.status, 2);
    assert.match(unavailable.stderr, /Memory unavailable; results incomplete/);
    assert.equal(spawnSync(process.execPath, [cli, "repair", "--session", session], { cwd: root, env }).status, 2);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("readers warn about tails without writes; explicit repair is separate", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-repair-"));
  const env = { PATH: "", HOME: root, XDG_DATA_HOME: root };
  const cli = path.join(repository, "cli.js");
  try {
    const { session, dir } = installSyntheticFixture(root);
    const log = path.join(dir, "events.jsonl");
    const clean = readFileSync(log, "utf8");
    const tail = '{"unfinished":';
    appendFileSync(log, tail);
    for (const args of [["list"], ["recall", "urn:claudiom:synthetic:reflection-1"], ["list", "--session", session]]) {
      const result = spawnSync(process.execPath, [cli, ...args], { cwd: root, env, encoding: "utf8" });
      assert.equal(result.status, 0);
      assert.match(result.stderr, new RegExp(`Repair needed for session ${path.basename(dir)}; results incomplete`));
      assert.equal(readFileSync(log, "utf8"), clean + tail);
      JSON.parse(result.stdout);
    }
    const missing = spawnSync(process.execPath, [cli, "recall", "urn:claudiom:synthetic:missing"],
      { cwd: root, env, encoding: "utf8" });
    assert.equal(missing.status, 1);
    assert.match(missing.stderr, /Repair needed for session/);
    assert.match(missing.stderr, /Memory not found\./);
    assert.equal(readFileSync(log, "utf8"), clean + tail);
    assert.deepEqual(JSON.parse(invoke(cli, ["repair", "--session", session], root, env)),
      { repaired: true, removed_bytes: Buffer.byteLength(tail) });
    assert.equal(readFileSync(log, "utf8"), clean);
    assert.deepEqual(JSON.parse(invoke(cli, ["repair", "--session", session], root, env)),
      { repaired: false, removed_bytes: 0 });
    const repaired = spawnSync(process.execPath, [cli, "list"], { cwd: root, env, encoding: "utf8" });
    assert.equal(repaired.stderr, "");
    assert.equal(spawnSync(process.execPath, [cli, "repair"], { cwd: root, env }).status, 2);
    assert.deepEqual(JSON.parse(invoke(cli, ["repair", "--session", "missing"], root, env)),
      { repaired: false, removed_bytes: 0 });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("an unrelated torn tail does not make a missing memory unavailable", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-missing-tail-"));
  const env = { PATH: "", HOME: root, XDG_DATA_HOME: root };
  const cli = path.join(repository, "cli.js");
  try {
    installSyntheticFixture(root);
    const { dir } = installSyntheticFixture(root, "unrelated");
    const log = path.join(dir, "events.jsonl");
    const event = JSON.parse(readFileSync(log, "utf8").split("\n")[0]);
    event.memories = [];
    const bytes = JSON.stringify(event) + '\n{"unfinished":';
    writeFileSync(log, bytes);
    const missing = spawnSync(process.execPath, [cli, "recall", "urn:claudiom:synthetic:missing"], { cwd: root, env, encoding: "utf8" });
    assert.equal(missing.status, 1);
    assert.equal(missing.stdout, "");
    assert.equal(missing.stderr, "Memory not found.\n");
    const listed = spawnSync(process.execPath, [cli, "list"], { cwd: root, env, encoding: "utf8" });
    assert.match(listed.stderr, new RegExp(`Repair needed for session ${path.basename(dir)}; results incomplete`));
    assert.match(missing.stderr, /Memory not found\./);
    assert.ok(!missing.stderr.includes("Memory unavailable"));
    assert.equal(readFileSync(log, "utf8"), bytes);
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
    const cli = path.join(pkg, "cli.js");
    assert.deepEqual(JSON.parse(invoke(cli, ["list"], consumer, env)), []);
    const { dir } = installSyntheticFixture(root);
    const before = readFileSync(path.join(dir, "events.jsonl"), "utf8");
    assert.deepEqual(JSON.parse(invoke(cli, ["list"], consumer, env)).map(memory => memory.id),
      ["urn:claudiom:synthetic:observation-1", "urn:claudiom:synthetic:reflection-1"]);
    const recalled = JSON.parse(invoke(cli, ["recall", "urn:claudiom:synthetic:reflection-1"], consumer, env));
    assert.equal(recalled.supporting_observations[0].text, "Synthetic decision.");
    assert.deepEqual(recalled.supports, ["urn:claudiom:synthetic:observation-1"]);
    assert.equal(recalled.supporting_observations[0].id, recalled.supports[0]);
    assert.equal(recalled.supporting_observations[0].citations[0].source_status, "not_checked");
    assert.deepEqual(JSON.parse(invoke(cli, ["recall", "urn:claudiom:synthetic:reflection-1"], consumer, env)), recalled);
    assert.equal(readFileSync(path.join(dir, "events.jsonl"), "utf8"), before);
    assert.ok(packed.files.some(file => file.path === "dist/claudiom.js"));
    assert.ok(packed.files.some(file => file.path === "LICENSE"));
    assert.equal(JSON.parse(readFileSync(path.join(pkg, "package.json"), "utf8")).license, "GPL-3.0-only");
    assert.match(readFileSync(path.join(pkg, "LICENSE"), "utf8"), /GNU GENERAL PUBLIC LICENSE/);
    assert.ok(!packed.files.some(file => file.path.startsWith("src/")));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
