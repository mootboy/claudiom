import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = fileURLToPath(new URL("../", import.meta.url));
const manifest = JSON.parse(readFileSync(path.join(repository, "package.json"), "utf8"));
const invoke = (cli, args, cwd, env) =>
  execFileSync(process.execPath, [cli, ...args], { cwd, env, encoding: "utf8", timeout: 15000 });

test("compiled CLI is inert and rejects unknown commands", () => {
  const root = mkdtempSync(path.join(tmpdir(), "claudiom-cli-"));
  const env = { PATH: "", HOME: root, XDG_CONFIG_HOME: root, XDG_DATA_HOME: root, XDG_STATE_HOME: root };
  try {
    const cli = path.join(repository, "cli.js");
    const result = JSON.parse(invoke(cli, ["status"], root, env));
    assert.equal(result.version, manifest.version);
    assert.equal(result.enabled, false);
    assert.equal(result.implemented, false);
    assert.match(invoke(cli, ["--help"], root, env), /Bootstrap only/);
    assert.equal(spawnSync(process.execPath, [cli, "unknown"], { cwd: root, env }).status, 1);
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
    assert.ok(packed.files.some(file => file.path === "dist/claudiom.js"));
    assert.ok(packed.files.some(file => file.path === "LICENSE"));
    assert.equal(JSON.parse(readFileSync(path.join(pkg, "package.json"), "utf8")).license, "GPL-3.0-only");
    assert.match(readFileSync(path.join(pkg, "LICENSE"), "utf8"), /GNU GENERAL PUBLIC LICENSE/);
    assert.ok(!packed.files.some(file => file.path.startsWith("src/")));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
