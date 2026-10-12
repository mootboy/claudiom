#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { start } from "./dist/claudiom.js";

const { version } = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));
start(process.argv.slice(2), version);
