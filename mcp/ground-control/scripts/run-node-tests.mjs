#!/usr/bin/env node
// Wrapper around `node --test` that fails when any test failed.
//
// `node --test` exits 0 when a `describe()` callback itself throws: the suite is
// printed as `not ok` with the error, but it is not counted in the `# fail`
// total and the process still reports success. A suite broken by a bad import
// or a typo therefore passes CI silently -- the same shape of green-but-blind
// gate that issue #1467 was opened over, and the one that hid a dropped import
// while this repo's test files were being split.
//
// This wrapper treats three things as failure: a non-zero child exit, a
// non-zero `# fail` count, and any `not ok` line. It also fails when no TAP
// summary was produced at all, so a runner that matched no files cannot be
// mistaken for a clean run.

import { spawn } from "node:child_process";

const args = process.argv.slice(2);
if (args.length === 0) {
  console.error("run-node-tests: no test files given");
  process.exit(2);
}

const child = spawn(
  process.execPath,
  ["--test", "--test-reporter=tap", ...args],
  { stdio: ["inherit", "pipe", "inherit"] },
);

let output = "";
child.stdout.on("data", (chunk) => {
  output += chunk;
  process.stdout.write(chunk);
});

child.on("close", (code, signal) => {
  const reasons = [];
  if (signal) reasons.push(`runner terminated by signal ${signal}`);
  else if (code !== 0) reasons.push(`runner exited ${code}`);

  const notOk = output.split("\n").filter((line) => /^\s*not ok\s/.test(line));
  if (notOk.length > 0) {
    reasons.push(`${notOk.length} failing assertion(s) or suite(s):`);
    reasons.push(...notOk.slice(0, 20).map((line) => `    ${line.trim()}`));
  }

  const failCount = /^# fail (\d+)$/m.exec(output);
  if (failCount && Number(failCount[1]) > 0) {
    reasons.push(`# fail ${failCount[1]}`);
  }
  const total = /^# tests (\d+)$/m.exec(output);
  if (!total) {
    reasons.push("no TAP summary emitted -- the runner produced no results");
  } else if (Number(total[1]) === 0) {
    // An unmatched glob reaches the runner as a literal path and still yields a
    // clean `# tests 0` summary, so "ran nothing" would otherwise read as pass.
    reasons.push("ran 0 tests -- the file list or glob matched nothing");
  }

  if (reasons.length > 0) {
    console.error(`\nrun-node-tests: FAILED\n  ${reasons.join("\n  ")}`);
    process.exit(1);
  }
  process.exit(0);
});
