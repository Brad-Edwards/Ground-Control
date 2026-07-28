// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  TEST_CASE_FORMATS,
  TEST_CASE_PRIORITIES,
  TEST_CASE_STATUSES,
  TEST_CASE_TYPES,
  TEST_PLAN_STATUSES,
  copyTestCase,
  createTestCase,
  createTestCaseFolder,
  createTestCaseGherkin,
  createTestCaseStep,
  createTestPlan,
  deleteTestCase,
  deleteTestCaseFolder,
  deleteTestCaseGherkin,
  deleteTestCaseStep,
  deleteTestPlan,
  moveTestCase,
  moveTestCaseFolder,
  pick,
  reorderTestCaseFolders,
  reorderTestCases,
  reqArg,
  transitionTestCaseStatus,
  transitionTestPlanStatus,
  updateTestCase,
  updateTestCaseFolder,
  updateTestCaseGherkin,
  updateTestCaseStep,
  updateTestPlan,
} from "../lib.js";
import { ok, err } from "./respond.js";

export const TEST_CASE_ACTIONS = [
  "create", "update", "delete", "transition",
  "step-create", "step-update", "step-delete",
  "gherkin-create", "gherkin-update", "gherkin-delete",
  // TC-005 / ADR-043 — Hierarchical organisation actions.
  "folder-create", "folder-update", "folder-delete", "folder-move", "folder-reorder",
  "move", "copy", "reorder",
];

export const TEST_PLAN_ACTIONS = ["create", "update", "delete", "transition"];


export function registerTestCase(server, ctx) {
  server.tool(
    "gc_test_case",
    `Test case operations (TC-001 / ADR-040 + TC-002 / ADR-041 + TC-004 / ADR-042). ` +
      `Actions: ${TEST_CASE_ACTIONS.join(", ")}. ` +
      `Reads (list, get, get-by-uid, step-list, step-get, gherkin-get) route through gc_query. ` +
      `Required fields per action: create→{uid,title,type,priority}; update/delete/move→{id}; transition→{id,status}; step-create→{test_case_id,step_number,step_action,expected_result}; step-update/step-delete→{test_case_id,step_id}; gherkin-create/gherkin-update→{test_case_id,gherkin_source}; gherkin-delete→{test_case_id}; folder-create→{folder_title}; folder-update/folder-delete/folder-move→{folder_id}; folder-reorder→{ordered_folder_ids}; copy→{id,new_uid}; reorder→{ordered_test_case_ids}.`,
    {
      action: z.enum(TEST_CASE_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      uid: z.string().optional(),
      title: z.string().optional(),
      type: z.enum(TEST_CASE_TYPES).optional(),
      priority: z.enum(TEST_CASE_PRIORITIES).optional(),
      // TC-004 / ADR-042 — authored format axis. Optional on create (defaults to
      // STEP_BASED server-side). Immutable after create.
      format: z.enum(TEST_CASE_FORMATS).optional(),
      description: z.string().optional(),
      preconditions: z.string().optional(),
      postconditions: z.string().optional(),
      estimated_duration_seconds: z.number().int().nonnegative().nullable().optional(),
      status: z.enum(TEST_CASE_STATUSES).optional(),
      // Partial-update clear flags (TC-001 codex cycle 1) — UpdateTestCaseRequest
      // accepts these on update so a client can wipe a nullable text/duration
      // field. Sending clearX=true overrides any non-null value in the same body.
      clear_description: z.boolean().optional(),
      clear_preconditions: z.boolean().optional(),
      clear_postconditions: z.boolean().optional(),
      clear_estimated_duration: z.boolean().optional(),
      // TC-002 step actions. test_case_id is the parent test case; step_id is the
      // step itself (step-update / step-delete). step_number is the per-test-case
      // ordering value. action / expected_result / actual_result are the step's
      // rich-text fields (CommonMark Markdown by convention per ADR-041).
      test_case_id: z.string().uuid().optional(),
      step_id: z.string().uuid().optional(),
      step_number: z.number().int().positive().optional(),
      step_action: z.string().optional(),
      expected_result: z.string().optional(),
      actual_result: z.string().nullable().optional(),
      clear_actual_result: z.boolean().optional(),
      // TC-004 Gherkin action body — `gherkin_source` is the MCP arg (namespaced
      // to avoid clashing with any other "source" field on future test_case
      // sub-resources); handler maps it to backend body `{ source }`.
      gherkin_source: z.string().optional(),
      // TC-005 / ADR-043 — folder + move/copy/reorder action fields.
      folder_id: z.string().uuid().optional(),
      parent_folder_id: z.string().uuid().nullable().optional(),
      sort_order: z.number().int().nonnegative().nullable().optional(),
      folder_title: z.string().optional(),
      folder_description: z.string().nullable().optional(),
      clear_folder_description: z.boolean().optional(),
      new_uid: z.string().optional(),
      ordered_folder_ids: z.array(z.string().uuid()).optional(),
      ordered_test_case_ids: z.array(z.string().uuid()).optional(),
    },
    async (args) => {
      try {
        const ENTITY_FIELDS = [
          "uid", "title", "type", "priority", "format", "description",
          "preconditions", "postconditions", "estimated_duration_seconds",
          "clear_description", "clear_preconditions", "clear_postconditions",
          "clear_estimated_duration",
          // TC-005 / ADR-043 — Placement fields on create only. The
          // backend's UpdateTestCaseRequest does NOT carry parent_folder_id
          // or sort_order; if those were in the update allowlist the MCP
          // call would accept them silently and Spring would drop them at
          // deserialization (codex cycle-2 finding). Update uses a separate
          // allowlist below; move/copy/reorder are dedicated actions.
          "parent_folder_id", "sort_order",
        ];
        const UPDATE_ENTITY_FIELDS = [
          "title", "type", "priority", "description",
          "preconditions", "postconditions", "estimated_duration_seconds",
          "clear_description", "clear_preconditions", "clear_postconditions",
          "clear_estimated_duration",
        ];
        // TC-005 / ADR-043 — TestCaseFolder request bodies. `folder_title` and
        // `folder_description` are MCP-side names that map to the backend's
        // `title` / `description` via lib.js FIELD_NAME_MAP; on update,
        // `clear_folder_description` maps to `clearDescription`.
        const folderCreateBody = () => {
          const body = pick(args, ["parent_folder_id", "sort_order"]);
          if (args.folder_title !== undefined) body.title = args.folder_title;
          if (args.folder_description !== undefined) body.description = args.folder_description;
          return body;
        };
        const folderUpdateBody = () => {
          const body = {};
          if (args.folder_title !== undefined) body.title = args.folder_title;
          if (args.folder_description !== undefined) body.description = args.folder_description;
          if (args.clear_folder_description !== undefined) body.clearDescription = args.clear_folder_description;
          return body;
        };
        const STEP_FIELDS = [
          "step_number", "expected_result", "actual_result", "clear_actual_result",
        ];
        // Map step_action → action so the MCP arg shape (which uses step_action
        // to avoid clashing with the existing action discriminator) lines up with
        // the backend's TestCaseStepRequest.action / .expectedResult / .actualResult.
        const stepBody = (extra) => {
          const body = pick(args, STEP_FIELDS);
          if (extra && extra.includeAction && args.step_action !== undefined) {
            body.action = args.step_action;
          }
          return body;
        };
        switch (args.action) {
          case "create": {
            reqArg(args, "uid", "create");
            reqArg(args, "title", "create");
            reqArg(args, "type", "create");
            reqArg(args, "priority", "create");
            return ok(JSON.stringify(await createTestCase(pick(args, ENTITY_FIELDS), args.project), null, 2));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(
              await updateTestCase(args.id, pick(args, UPDATE_ENTITY_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteTestCase(args.id, args.project);
            return ok("Deleted");
          }
          case "transition": {
            reqArg(args, "id", "transition");
            reqArg(args, "status", "transition");
            return ok(JSON.stringify(
              await transitionTestCaseStatus(args.id, args.status, args.project),
              null,
              2,
            ));
          }
          case "step-create": {
            reqArg(args, "test_case_id", "step-create");
            reqArg(args, "step_number", "step-create");
            reqArg(args, "step_action", "step-create");
            reqArg(args, "expected_result", "step-create");
            return ok(JSON.stringify(
              await createTestCaseStep(args.test_case_id, stepBody({ includeAction: true }), args.project),
              null,
              2,
            ));
          }
          case "step-update": {
            reqArg(args, "test_case_id", "step-update");
            reqArg(args, "step_id", "step-update");
            return ok(JSON.stringify(
              await updateTestCaseStep(
                args.test_case_id,
                args.step_id,
                stepBody({ includeAction: true }),
                args.project,
              ),
              null,
              2,
            ));
          }
          case "step-delete": {
            reqArg(args, "test_case_id", "step-delete");
            reqArg(args, "step_id", "step-delete");
            await deleteTestCaseStep(args.test_case_id, args.step_id, args.project);
            return ok("Deleted");
          }
          case "gherkin-create": {
            reqArg(args, "test_case_id", "gherkin-create");
            reqArg(args, "gherkin_source", "gherkin-create");
            return ok(JSON.stringify(
              await createTestCaseGherkin(args.test_case_id, { source: args.gherkin_source }, args.project),
              null,
              2,
            ));
          }
          case "gherkin-update": {
            reqArg(args, "test_case_id", "gherkin-update");
            reqArg(args, "gherkin_source", "gherkin-update");
            return ok(JSON.stringify(
              await updateTestCaseGherkin(args.test_case_id, { source: args.gherkin_source }, args.project),
              null,
              2,
            ));
          }
          case "gherkin-delete": {
            reqArg(args, "test_case_id", "gherkin-delete");
            await deleteTestCaseGherkin(args.test_case_id, args.project);
            return ok("Deleted");
          }
          case "folder-create": {
            reqArg(args, "folder_title", "folder-create");
            return ok(JSON.stringify(
              await createTestCaseFolder(folderCreateBody(), args.project),
              null,
              2,
            ));
          }
          case "folder-update": {
            reqArg(args, "folder_id", "folder-update");
            return ok(JSON.stringify(
              await updateTestCaseFolder(args.folder_id, folderUpdateBody(), args.project),
              null,
              2,
            ));
          }
          case "folder-delete": {
            reqArg(args, "folder_id", "folder-delete");
            await deleteTestCaseFolder(args.folder_id, args.project);
            return ok("Deleted");
          }
          case "folder-move": {
            reqArg(args, "folder_id", "folder-move");
            return ok(JSON.stringify(
              await moveTestCaseFolder(
                args.folder_id,
                { parentFolderId: args.parent_folder_id ?? null, sortOrder: args.sort_order ?? null },
                args.project,
              ),
              null,
              2,
            ));
          }
          case "folder-reorder": {
            reqArg(args, "ordered_folder_ids", "folder-reorder");
            await reorderTestCaseFolders(
              { parentFolderId: args.parent_folder_id ?? null, orderedFolderIds: args.ordered_folder_ids },
              args.project,
            );
            return ok("Reordered");
          }
          case "move": {
            reqArg(args, "id", "move");
            return ok(JSON.stringify(
              await moveTestCase(
                args.id,
                { parentFolderId: args.parent_folder_id ?? null, sortOrder: args.sort_order ?? null },
                args.project,
              ),
              null,
              2,
            ));
          }
          case "copy": {
            reqArg(args, "id", "copy");
            reqArg(args, "new_uid", "copy");
            return ok(JSON.stringify(
              await copyTestCase(
                args.id,
                {
                  newUid: args.new_uid,
                  parentFolderId: args.parent_folder_id ?? null,
                  sortOrder: args.sort_order ?? null,
                },
                args.project,
              ),
              null,
              2,
            ));
          }
          case "reorder": {
            reqArg(args, "ordered_test_case_ids", "reorder");
            await reorderTestCases(
              { parentFolderId: args.parent_folder_id ?? null, orderedTestCaseIds: args.ordered_test_case_ids },
              args.project,
            );
            return ok("Reordered");
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_test_plan",
    `Test plan operations (TC-006 / ADR-044). ` +
      `Actions: ${TEST_PLAN_ACTIONS.join(", ")}. ` +
      `Reads (list, get, get-by-uid) route through gc_query. ` +
      `Required fields per action: create→{uid,name}; update/delete→{id}; transition→{id,status}.`,
    {
      action: z.enum(TEST_PLAN_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      uid: z.string().optional(),
      name: z.string().optional(),
      description: z.string().optional(),
      product: z.string().optional(),
      version: z.string().optional(),
      build: z.string().optional(),
      status: z.enum(TEST_PLAN_STATUSES).optional(),
      // Dates accepted as ISO-8601 strings (YYYY-MM-DD); Jackson binds them to
      // LocalDate on the backend.
      start_date: z.string().optional(),
      end_date: z.string().optional(),
      // Partial-update clear flags. Sending clear_*: true overrides any non-null
      // value for the same field in the same request body.
      clear_description: z.boolean().optional(),
      clear_product: z.boolean().optional(),
      clear_version: z.boolean().optional(),
      clear_build: z.boolean().optional(),
      clear_start_date: z.boolean().optional(),
      clear_end_date: z.boolean().optional(),
    },
    async (args) => {
      try {
        const TEST_PLAN_CREATE_FIELDS = [
          "uid", "name", "description", "product", "version", "build",
          "start_date", "end_date",
        ];
        const TEST_PLAN_UPDATE_FIELDS = [
          "name", "description", "product", "version", "build",
          "start_date", "end_date",
          "clear_description", "clear_product", "clear_version", "clear_build",
          "clear_start_date", "clear_end_date",
        ];
        switch (args.action) {
          case "create": {
            reqArg(args, "uid", "create");
            reqArg(args, "name", "create");
            return ok(JSON.stringify(
              await createTestPlan(pick(args, TEST_PLAN_CREATE_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(
              await updateTestPlan(args.id, pick(args, TEST_PLAN_UPDATE_FIELDS), args.project),
              null,
              2,
            ));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteTestPlan(args.id, args.project);
            return ok("Deleted");
          }
          case "transition": {
            reqArg(args, "id", "transition");
            reqArg(args, "status", "transition");
            return ok(JSON.stringify(
              await transitionTestPlanStatus(args.id, args.status, args.project),
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
