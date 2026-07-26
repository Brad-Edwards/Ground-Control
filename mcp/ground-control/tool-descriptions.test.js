// Parity regression test: verify that every action-multiplexed MCP tool's
// published description contains the required field tokens it enforces at
// runtime. Spawns the real MCP server as a subprocess and queries it via the
// SDK client so the assertion targets the live published surface, not a static
// string in source. Addresses issue #1169.

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const DIR = fileURLToPath(new URL(".", import.meta.url));

// Required field tokens per tool. Each token is a distinctive field name that
// the tool description must contain because the tool enforces it at runtime
// (via reqArg or equivalent). Tokens are substrings of field names; the check
// is description.includes(token).
const REQUIRED_FIELD_REGISTRY = {
  gc_relation: ["source_id", "target_id", "relation_type", "requirement_id"],
  gc_adr: ["uid", "title", "status"],
  gc_document: ["title", "grammar"],
  gc_section: ["document_id", "content_type", "content_id"],
  gc_quality_gate: ["name", "metric_type", "evaluate→"],
  gc_test_case: [
    "uid", "title", "type", "priority",
    "test_case_id", "step_number", "step_action", "expected_result",
    "gherkin_source", "folder_title", "folder_id",
    "ordered_folder_ids", "new_uid", "ordered_test_case_ids",
  ],
  gc_test_plan: ["uid", "name"],
  gc_test_suite: [
    "uid", "name", "population_mode",
    "test_case_id", "ordered_test_case_ids", "requirement_id",
  ],
  gc_test_run: [
    "uid", "name", "test_plan_id", "test_suite_id",
    "tester_name", "result_status", "case_result_id",
    "step_result_id", "step_status",
  ],
  gc_asset: [
    "uid", "name", "asset_type",
    "source_id", "target_id", "relation_type", "relation_id",
    "roots", "target_type", "link_type", "link_id",
    "namespace", "external_id", "external_id_record_id",
    "subtype", "schema_version", "schema_body", "schema_id",
  ],
  gc_control: [
    "control_id", "uid", "title", "status",
    "target_type", "link_type", "link_id",
  ],
  // ADR-089 §1/§3 retired methodology_profile, risk_register_record,
  // risk_assessment_result, treatment_plan, and risk_appetite_profile from
  // gc_risk_governance; only verification_result remains. gc_grc_assess
  // (the standalone GRC assessment lane tool) was removed entirely.
  gc_risk_governance: [
    "target_id", "requirement_id", "prover", "property",
    "result", "assurance_level", "evidence", "verified_at", "expires_at",
  ],
  gc_requirement: ["uid", "title", "statement", "source_uid", "new_uid"],
  gc_baseline: ["name", "baseline_a", "baseline_b"],
  gc_graph: ["uid", "source", "target", "roots"],
  gc_observation: [
    "asset_id", "category", "observation_key",
    "observation_value", "source", "observed_at",
  ],
  gc_risk_scenario: [
    "uid", "title", "threat", "method", "asset", "effect", "time_horizon",
  ],
  gc_threat_model: [
    "uid", "title", "threat_source", "threat_event", "effect",
  ],
  gc_prepare_implement_branch: [
    "repo_path", "invocation_root", "issue_number", "branch_name",
    "base_branch", "checkout_mode",
  ],
  gc_synchronize_implement_branch: [
    "repo_path", "issue_number", "branch_name", "action", "record_id",
    "pre_sync_sha", "fetched_base_sha", "outcome",
  ],
  gc_create_synchronized_implement_pr: [
    "repo_path", "issue_number", "branch_name", "record_id", "title", "body",
  ],
  gc_record_execution_obligation: [
    "obligation_id", "event", "category", "observed_state", "evidence",
    "impact", "obligation", "pause_class", "decision_request", "disposition",
    "corrective_action", "verification", "user_authorization",
  ],
  gc_mark_implement_issue_picked_up: [
    "repo_path", "issue_number", "driver", "branch_name",
  ],
  gc_authorize_execution_obligation_wontfix: [
    "repo_path", "issue_number", "obligation_id", "authorization_source_url",
  ],
};

describe("MCP tool description parity (issue #1169)", { timeout: 30000 }, () => {
  let client;
  let transport;
  let descriptionMap;

  before(async () => {
    transport = new StdioClientTransport({
      command: process.execPath,
      args: ["index.js"],
      cwd: DIR,
      stderr: "ignore",
    });
    client = new Client({ name: "desc-parity-test", version: "1.0.0" });
    await client.connect(transport);

    const { tools } = await client.listTools();
    descriptionMap = Object.fromEntries(
      tools.map((t) => [t.name, t.description ?? ""]),
    );
  });

  after(async () => {
    if (client) await client.close();
  });

  for (const [toolName, tokens] of Object.entries(REQUIRED_FIELD_REGISTRY)) {
    it(`${toolName}: description contains all required field tokens`, () => {
      const description = descriptionMap[toolName];
      assert.ok(
        description !== undefined,
        `Tool '${toolName}' not found in listTools() response`,
      );
      for (const token of tokens) {
        assert.ok(
          description.includes(token),
          `Tool '${toolName}': description missing token '${token}'`,
        );
      }
    });
  }
});
