// Every `gc_*` tool a skill names must be registered by this server.
//
// #1506 removed `gc_integration_manager` as dead code after checking for callers
// in JS and finding none — but the caller was `skills/integrate/SKILL.md`, which
// invokes tools by name in prose. That left GC-O011 (ACTIVE, MUST) with no entry
// point and a `/integrate` lane that could not run, and nothing failed, because
// no test crossed the prose-to-registration boundary. This one does: it reads the
// tool names out of every skill and asserts the live server advertises each one.

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, realpathSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const DIR = fileURLToPath(new URL(".", import.meta.url));
const REPO_ROOT = realpathSync(new URL("../..", import.meta.url).pathname);
const SKILLS_DIR = `${REPO_ROOT}/skills`;

// Markdown files that carry a skill's executable prose. A skill is one directory
// with a SKILL.md, optionally with per-step files under steps/.
function skillMarkdownFiles() {
  const files = [];
  for (const entry of readdirSync(SKILLS_DIR)) {
    const skillRoot = `${SKILLS_DIR}/${entry}`;
    if (!statSync(skillRoot).isDirectory()) continue;
    const skillFile = `${skillRoot}/SKILL.md`;
    try {
      if (statSync(skillFile).isFile()) files.push(skillFile);
    } catch { continue; }
    try {
      for (const step of readdirSync(`${skillRoot}/steps`)) {
        if (step.endsWith(".md")) files.push(`${skillRoot}/steps/${step}`);
      }
    } catch { /* a skill without per-step files is normal */ }
  }
  return files;
}

function referencedToolNames() {
  const referenced = new Map();
  for (const file of skillMarkdownFiles()) {
    for (const match of readFileSync(file, "utf8").matchAll(/\bgc_[a-z0-9_]+\b/g)) {
      const rel = file.slice(REPO_ROOT.length + 1);
      if (!referenced.has(match[0])) referenced.set(match[0], new Set());
      referenced.get(match[0]).add(rel);
    }
  }
  return referenced;
}

describe("skill prose names only registered tools (GC-O011 regression)", { timeout: 30000 }, () => {
  let client;
  let registered;

  before(async () => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: ["index.js"],
      cwd: DIR,
      stderr: "ignore",
    });
    client = new Client({ name: "skill-tool-contract-test", version: "1.0.0" });
    await client.connect(transport);
    const { tools } = await client.listTools();
    registered = new Set(tools.map((t) => t.name));
  });

  after(async () => {
    if (client) await client.close();
  });

  it("finds skill prose to check", () => {
    assert.ok(skillMarkdownFiles().length > 0, "no skill markdown found");
    assert.ok(referencedToolNames().size > 0, "no gc_* tool names found in skill prose");
  });

  it("registers every tool the skills invoke", () => {
    const missing = [];
    for (const [tool, sources] of referencedToolNames()) {
      if (!registered.has(tool)) missing.push(`${tool} (named in ${[...sources].join(", ")})`);
    }
    assert.deepEqual(
      missing,
      [],
      "skill prose names tools this server does not register. Either register them or "
      + "remove the lane; a skill that calls an unregistered tool is a lane that cannot run:\n"
      + missing.join("\n"),
    );
  });

  it("registers gc_integration_manager for the /integrate lane", () => {
    assert.ok(
      registered.has("gc_integration_manager"),
      "GC-O011 is ACTIVE and skills/integrate/SKILL.md drives the lane through this tool",
    );
  });
});
