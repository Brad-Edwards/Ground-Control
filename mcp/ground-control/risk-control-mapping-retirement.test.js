// Regression guard for issue #1309 (ADR-084 §5): the `threats-insufficient-effectiveness`
// gc_risk_control_mapping action (and its `as_of` parameter) called a REST route that ADR-089 /
// V199 retired — RiskControlAnalysisController never had it, only unmapped-scenarios,
// unmapped-controls, unmapped-threats, and threat-unmapped-controls. It was dead, divergent
// "as-of" surface: contract drift, not a feature, and no temporal semantics reimplemented in
// JavaScript. This test spawns the real MCP server (matching tool-descriptions.test.js) so the
// assertion targets the live published schema, not a static string in source, and would fail if
// the action or its `as_of` parameter were ever reintroduced.

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import * as lib from "./lib.js";

const DIR = fileURLToPath(new URL(".", import.meta.url));

describe("gc_risk_control_mapping: threats-insufficient-effectiveness retirement", { timeout: 30000 }, () => {
  let client;
  let transport;
  let tool;

  before(async () => {
    transport = new StdioClientTransport({
      command: process.execPath,
      args: ["index.js"],
      cwd: DIR,
      stderr: "ignore",
    });
    client = new Client({ name: "rcm-retirement-test", version: "1.0.0" });
    await client.connect(transport);

    const { tools } = await client.listTools();
    tool = tools.find((t) => t.name === "gc_risk_control_mapping");
  });

  after(async () => {
    if (client) await client.close();
  });

  it("does not publish the retired threats-insufficient-effectiveness action", () => {
    assert.ok(tool, "gc_risk_control_mapping tool not found in listTools() response");
    const actionEnum = tool.inputSchema?.properties?.action?.enum ?? [];
    assert.ok(
      !actionEnum.includes("threats-insufficient-effectiveness"),
      `action enum still includes the retired action: ${JSON.stringify(actionEnum)}`,
    );
  });

  it("does not publish the as_of parameter that only the retired action used", () => {
    const properties = tool.inputSchema?.properties ?? {};
    assert.ok(
      !Object.hasOwn(properties, "as_of"),
      "as_of parameter still present on gc_risk_control_mapping — no asOf-shaped surface may bypass the resolver",
    );
  });

  it("does not publish min_effectiveness / freshness_window_days (only used by the retired action)", () => {
    const properties = tool.inputSchema?.properties ?? {};
    assert.ok(!Object.hasOwn(properties, "min_effectiveness"));
    assert.ok(!Object.hasOwn(properties, "freshness_window_days"));
  });

  it("no longer exports getThreatsInsufficientEffectiveness from lib.js", () => {
    assert.equal(lib.getThreatsInsufficientEffectiveness, undefined);
  });
});
