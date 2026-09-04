// Regression test for issue #633: the version the MCP server advertises in the
// initialize handshake must be the package's own version, not a literal that
// silently stays at 1.0.0 while tool schemas change underneath it. Spawns the
// real server and reads serverInfo over the wire so the assertion targets the
// published handshake rather than a source string.

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import pkg from "./package.json" with { type: "json" };

const DIR = fileURLToPath(new URL(".", import.meta.url));

describe("MCP server version (issue #633)", { timeout: 30000 }, () => {
  let client;
  let serverInfo;

  before(async () => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: ["index.js"],
      cwd: DIR,
      stderr: "ignore",
    });
    client = new Client({ name: "server-version-test", version: "1.0.0" });
    await client.connect(transport);
    serverInfo = client.getServerVersion();
  });

  after(async () => {
    if (client) await client.close();
  });

  it("advertises the package.json version, not a hard-coded literal", () => {
    assert.equal(serverInfo?.name, "ground-control");
    assert.equal(serverInfo?.version, pkg.version);
  });

  it("declares a semantic version so clients can gate on a breaking bump", () => {
    assert.match(pkg.version, /^\d+\.\d+\.\d+$/);
  });
});
