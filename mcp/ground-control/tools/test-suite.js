// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  STATUSES,
  TEST_CASE_FORMATS,
  TEST_CASE_PRIORITIES,
  TEST_CASE_STATUSES,
  TEST_CASE_TYPES,
  TEST_RUN_CASE_RESULT_STATUSES,
  TEST_RUN_STATUSES,
  TEST_SUITE_POPULATION_MODES,
  addTestRunTester,
  addTestSuiteMember,
  addTestSuiteSourceRequirement,
  createTestRun,
  createTestSuite,
  deleteTestRun,
  deleteTestSuite,
  listTestRunStepResults,
  pick,
  removeTestRunTester,
  removeTestSuiteMember,
  removeTestSuiteSourceRequirement,
  reorderTestSuiteMembers,
  reqArg,
  resolveTestSuiteTestCases,
  transitionTestRunStatus,
  updateTestRun,
  updateTestRunCaseResult,
  updateTestRunCursor,
  updateTestRunStepResult,
  updateTestSuite,
} from "../lib.js";
import { ok, err } from "./respond.js";

export const TEST_SUITE_ACTIONS = [
  "create",
  "update",
  "delete",
  "resolve",
  "add_member",
  "remove_member",
  "reorder_members",
  "add_source_requirement",
  "remove_source_requirement",
];

export const TEST_RUN_ACTIONS = [
  "create",
  "update",
  "delete",
  "transition",
  "add_tester",
  "remove_tester",
  "update_result",
  // TC-009 / ADR-050 — runner ops.
  "list_step_results",
  "update_step_result",
  "update_cursor",
];


export function registerTestSuite(server, ctx) {
  server.tool(
    "gc_test_suite",
    `Test suite operations (TC-007 / ADR-047). ` +
      `Actions: ${TEST_SUITE_ACTIONS.join(", ")}. ` +
      `Reads (list, get, get-by-uid) route through gc_query. ` +
      `Required fields per action: create→{uid,name,population_mode}; update/delete/resolve→{id}; add_member/remove_member→{id,test_case_id}; reorder_members→{id,ordered_test_case_ids}; add_source_requirement/remove_source_requirement→{id,requirement_id}.`,
    {
      action: z.enum(TEST_SUITE_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      uid: z.string().optional(),
      name: z.string().optional(),
      description: z.string().optional(),
      population_mode: z.enum(TEST_SUITE_POPULATION_MODES).optional(),
      // QUERY_BASED criteria (only valid for QUERY_BASED suites — backend
      // rejects with 422 invalid_test_suite_mode_field on other modes).
      // Codex pre-push cycle 1 F6: use the test-case enum mirrors (not the
      // requirements `STATUSES` mirror, which would let "ACTIVE" reach the
      // backend and miss "APPROVED").
      criteria_status: z.enum(TEST_CASE_STATUSES).optional(),
      criteria_type: z.enum(TEST_CASE_TYPES).optional(),
      criteria_priority: z.enum(TEST_CASE_PRIORITIES).optional(),
      criteria_format: z.enum(TEST_CASE_FORMATS).optional(),
      criteria_folder_id: z.string().uuid().optional(),
      criteria_text_search: z.string().optional(),
      // Partial-update clear flags for the criteria block.
      clear_description: z.boolean().optional(),
      clear_criteria_status: z.boolean().optional(),
      clear_criteria_type: z.boolean().optional(),
      clear_criteria_priority: z.boolean().optional(),
      clear_criteria_format: z.boolean().optional(),
      clear_criteria_folder_id: z.boolean().optional(),
      clear_criteria_text_search: z.boolean().optional(),
      // STATIC-mode member ops.
      test_case_id: z.string().uuid().optional(),
      position: z.number().int().nonnegative().optional(),
      ordered_test_case_ids: z.array(z.string().uuid()).optional(),
      // REQUIREMENTS_BASED-mode source ops.
      requirement_id: z.string().uuid().optional(),
    },
    async (args) => {
      try {
        const TEST_SUITE_CREATE_FIELDS = [
          "uid", "name", "description", "population_mode",
          "criteria_status", "criteria_type", "criteria_priority",
          "criteria_format", "criteria_folder_id", "criteria_text_search",
        ];
        const TEST_SUITE_UPDATE_FIELDS = [
          "name", "description",
          "criteria_status", "criteria_type", "criteria_priority",
          "criteria_format", "criteria_folder_id", "criteria_text_search",
          "clear_description",
          "clear_criteria_status", "clear_criteria_type", "clear_criteria_priority",
          "clear_criteria_format", "clear_criteria_folder_id", "clear_criteria_text_search",
        ];
        switch (args.action) {
          case "create": {
            reqArg(args, "uid", "create");
            reqArg(args, "name", "create");
            reqArg(args, "population_mode", "create");
            return ok(JSON.stringify(
              await createTestSuite(pick(args, TEST_SUITE_CREATE_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(
              await updateTestSuite(args.id, pick(args, TEST_SUITE_UPDATE_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteTestSuite(args.id, args.project);
            return ok("Deleted");
          }
          case "resolve": {
            reqArg(args, "id", "resolve");
            return ok(JSON.stringify(
              await resolveTestSuiteTestCases(args.id, args.project),
              null,
              2,
            ));
          }
          case "add_member": {
            reqArg(args, "id", "add_member");
            reqArg(args, "test_case_id", "add_member");
            return ok(JSON.stringify(
              await addTestSuiteMember(
                args.id,
                pick(args, ["test_case_id", "position"]),
                args.project,
              ),
              null,
              2,
            ));
          }
          case "remove_member": {
            reqArg(args, "id", "remove_member");
            reqArg(args, "test_case_id", "remove_member");
            await removeTestSuiteMember(args.id, args.test_case_id, args.project);
            return ok("Removed");
          }
          case "reorder_members": {
            reqArg(args, "id", "reorder_members");
            reqArg(args, "ordered_test_case_ids", "reorder_members");
            return ok(JSON.stringify(
              await reorderTestSuiteMembers(args.id, args.ordered_test_case_ids, args.project),
              null,
              2,
            ));
          }
          case "add_source_requirement": {
            reqArg(args, "id", "add_source_requirement");
            reqArg(args, "requirement_id", "add_source_requirement");
            return ok(JSON.stringify(
              await addTestSuiteSourceRequirement(
                args.id,
                { requirement_id: args.requirement_id },
                args.project,
              ),
              null,
              2,
            ));
          }
          case "remove_source_requirement": {
            reqArg(args, "id", "remove_source_requirement");
            reqArg(args, "requirement_id", "remove_source_requirement");
            await removeTestSuiteSourceRequirement(args.id, args.requirement_id, args.project);
            return ok("Removed");
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_test_run",
    `Test run operations (TC-008 / ADR-049). ` +
      `Actions: ${TEST_RUN_ACTIONS.join(", ")}. ` +
      `Reads (list, get, get-by-uid, testers, results) route through gc_query. ` +
      `Required fields per action: create→{uid,name,test_plan_id,test_suite_id}; update/delete/update_cursor→{id}; transition→{id,status}; add_tester/remove_tester→{id,tester_name}; update_result→{id,test_case_id,result_status}; list_step_results→{id,case_result_id}; update_step_result→{id,case_result_id,step_result_id,step_status}.`,
    {
      action: z.enum(TEST_RUN_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      uid: z.string().optional(),
      name: z.string().optional(),
      test_plan_id: z.string().uuid().optional(),
      test_suite_id: z.string().uuid().optional(),
      environment: z.string().optional(),
      version: z.string().optional(),
      build: z.string().optional(),
      status: z.enum(TEST_RUN_STATUSES).optional(),
      // Timestamps accepted as ISO-8601 strings (e.g. "2026-06-01T00:00:00Z");
      // Jackson binds them to Instant on the backend.
      start_at: z.string().optional(),
      end_at: z.string().optional(),
      // Partial-update clear flags.
      clear_environment: z.boolean().optional(),
      clear_version: z.boolean().optional(),
      clear_build: z.boolean().optional(),
      clear_start_at: z.boolean().optional(),
      clear_end_at: z.boolean().optional(),
      // Tester ops.
      tester_name: z.string().optional(),
      // Per-case result ops.
      test_case_id: z.string().uuid().optional(),
      result_status: z.enum(TEST_RUN_CASE_RESULT_STATUSES).optional(),
      notes: z.string().optional(),
      clear_notes: z.boolean().optional(),
      // TC-009 / ADR-050 — runner ops.
      case_result_id: z.string().uuid().optional(),
      step_result_id: z.string().uuid().optional(),
      step_status: z.enum(TEST_RUN_CASE_RESULT_STATUSES).optional(),
      comment: z.string().optional(),
      clear_comment: z.boolean().optional(),
      executed_at: z.string().optional(),
      clear_executed_at: z.boolean().optional(),
      current_case_result_id: z.string().uuid().optional(),
      current_step_result_id: z.string().uuid().optional(),
      clear_cursor: z.boolean().optional(),
    },
    async (args) => {
      try {
        const TEST_RUN_CREATE_FIELDS = [
          "uid", "name", "test_plan_id", "test_suite_id",
          "environment", "version", "build", "start_at", "end_at",
        ];
        const TEST_RUN_UPDATE_FIELDS = [
          "name", "environment", "version", "build", "start_at", "end_at",
          "clear_environment", "clear_version", "clear_build",
          "clear_start_at", "clear_end_at",
        ];
        switch (args.action) {
          case "create": {
            reqArg(args, "uid", "create");
            reqArg(args, "name", "create");
            reqArg(args, "test_plan_id", "create");
            reqArg(args, "test_suite_id", "create");
            return ok(JSON.stringify(
              await createTestRun(pick(args, TEST_RUN_CREATE_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(
              await updateTestRun(args.id, pick(args, TEST_RUN_UPDATE_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteTestRun(args.id, args.project);
            return ok("Deleted");
          }
          case "transition": {
            reqArg(args, "id", "transition");
            reqArg(args, "status", "transition");
            return ok(JSON.stringify(
              await transitionTestRunStatus(args.id, args.status, args.project),
              null,
              2,
            ));
          }
          case "add_tester": {
            reqArg(args, "id", "add_tester");
            reqArg(args, "tester_name", "add_tester");
            return ok(JSON.stringify(
              await addTestRunTester(args.id, args.tester_name, args.project),
              null,
              2,
            ));
          }
          case "remove_tester": {
            reqArg(args, "id", "remove_tester");
            reqArg(args, "tester_name", "remove_tester");
            await removeTestRunTester(args.id, args.tester_name, args.project);
            return ok("Removed");
          }
          case "update_result": {
            reqArg(args, "id", "update_result");
            reqArg(args, "test_case_id", "update_result");
            reqArg(args, "result_status", "update_result");
            // Field renaming: the MCP surface exposes `result_status` to keep
            // it disambiguated from the run-level `status`; the backend DTO
            // takes `status`. Build the payload explicitly so toCamelCase
            // does the snake-camel mapping for the rest of the body.
            const payload = { status: args.result_status };
            if (args.notes !== undefined) payload.notes = args.notes;
            if (args.clear_notes !== undefined) payload.clearNotes = args.clear_notes;
            return ok(JSON.stringify(
              await updateTestRunCaseResult(args.id, args.test_case_id, payload, args.project),
              null,
              2,
            ));
          }
          case "list_step_results": {
            // TC-009 — explicit MCP surface for the step-result read. The same
            // GET is reachable via gc_query under the /api/v1/test-runs
            // allow-list, but exposing it as a discoverable action makes the
            // runner end-to-end usable without callers having to know the URL
            // shape.
            reqArg(args, "id", "list_step_results");
            reqArg(args, "case_result_id", "list_step_results");
            return ok(JSON.stringify(
              await listTestRunStepResults(args.id, args.case_result_id, args.project),
              null,
              2,
            ));
          }
          case "update_step_result": {
            reqArg(args, "id", "update_step_result");
            reqArg(args, "case_result_id", "update_step_result");
            reqArg(args, "step_result_id", "update_step_result");
            reqArg(args, "step_status", "update_step_result");
            // Same `result_status`-style disambiguation: the runner-side
            // status is exposed as `step_status` so it never collides with
            // the run-level enum; the backend DTO field is plain `status`.
            const payload = { status: args.step_status };
            if (args.comment !== undefined) payload.comment = args.comment;
            if (args.clear_comment !== undefined) payload.clearComment = args.clear_comment;
            if (args.executed_at !== undefined) payload.executedAt = args.executed_at;
            if (args.clear_executed_at !== undefined) payload.clearExecutedAt = args.clear_executed_at;
            return ok(JSON.stringify(
              await updateTestRunStepResult(
                args.id,
                args.case_result_id,
                args.step_result_id,
                payload,
                args.project,
              ),
              null,
              2,
            ));
          }
          case "update_cursor": {
            reqArg(args, "id", "update_cursor");
            const payload = {};
            if (args.current_case_result_id !== undefined) payload.currentCaseResultId = args.current_case_result_id;
            if (args.current_step_result_id !== undefined) payload.currentStepResultId = args.current_step_result_id;
            if (args.clear_cursor !== undefined) payload.clearCursor = args.clear_cursor;
            return ok(JSON.stringify(
              await updateTestRunCursor(args.id, payload, args.project),
              null,
              2,
            ));
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );
}
