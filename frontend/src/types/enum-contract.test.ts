/**
 * Mechanical contract test: the frontend enum constant arrays in `api.ts` must
 * match the backend Java enums under
 * `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/`,
 * which are the single source of truth (ADR-034). This test reads the actual
 * Java source files and parses their enum constants — it does NOT hardcode a
 * second copy of the value lists, so it cannot "move the drift".
 *
 * The authoritative CI gate is `tools/policy/checks.py::run_enum_contract_check`
 * (run by `bin/policy` in the `policy` CI job), which also checks the `api.ts`
 * union types and the MCP `lib.js` constants and covers union-only mirrors
 * (SyncStatus, ChangeCategory). This test is the frontend-developer-local mirror
 * of that contract for the enums the UI iterates as arrays.
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  ARTIFACT_TYPES,
  AUDIT_STATUSES,
  AUDIT_TYPES,
  CHANGE_CATEGORIES,
  LINK_TYPES,
  PRIORITIES,
  RELATION_TYPES,
  REQUIREMENT_TYPES,
  STATUSES,
} from "./api";

const STATE_DIR =
  "../../../backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state";
const AUDIT_STATE_DIR =
  "../../../backend/src/main/java/com/keplerops/groundcontrol/domain/audits/state";

function javaEnumConstantsIn(
  stateDir: string,
  enumClassName: string,
): string[] {
  const path = fileURLToPath(
    new URL(`${stateDir}/${enumClassName}.java`, import.meta.url),
  );
  // Strip line and block comments so a commented-out constant is not counted.
  const source = readFileSync(path, "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/\/\/[^\n]*/g, "");
  // Body runs from the opening `{` to the first `;` (enums with methods/fields,
  // e.g. Status) or the closing `}` (constant-only enums); strip constructor
  // argument groups like FOO("x").
  const body = source
    .match(/enum\s+\w+[^{;]*\{([\s\S]*?)(?:;|\})/)?.[1]
    ?.replace(/\([^)]*\)/g, " ");
  if (body === undefined) {
    throw new Error(`No enum declaration found in ${enumClassName}.java`);
  }
  return body
    .split(/[,\s]+/)
    .map((token) => token.trim())
    .filter((token) => /^[A-Z][A-Z0-9_]*$/.test(token));
}

function javaEnumConstants(enumClassName: string): string[] {
  return javaEnumConstantsIn(STATE_DIR, enumClassName);
}

describe("frontend enum constants match the backend Java source of truth", () => {
  const cases: ReadonlyArray<readonly [string, readonly string[]]> = [
    ["Status", STATUSES],
    ["Priority", PRIORITIES],
    ["RequirementType", REQUIREMENT_TYPES],
    ["RelationType", RELATION_TYPES],
    ["ArtifactType", ARTIFACT_TYPES],
    ["LinkType", LINK_TYPES],
    ["ChangeCategory", CHANGE_CATEGORIES],
  ];

  for (const [enumClassName, frontendConstant] of cases) {
    it(`${enumClassName} — api.ts constant equals the Java enum constants (in order)`, () => {
      expect([...frontendConstant]).toEqual(javaEnumConstants(enumClassName));
    });
  }

  it("ArtifactType still includes the cross-workflow targets (regression for #433)", () => {
    // The earlier partial fix dropped these from the frontend even though the
    // backend/MCP keep them for sync, ADR, risk, and control workflows.
    for (const value of ["PULL_REQUEST", "RISK_SCENARIO", "CONTROL"]) {
      expect(ARTIFACT_TYPES).toContain(value);
    }
  });
});

describe("GC-U001 audit enum constants match the backend Java source of truth", () => {
  it("AuditStatus — api.ts constant equals the Java enum constants (in order)", () => {
    expect([...AUDIT_STATUSES]).toEqual(
      javaEnumConstantsIn(AUDIT_STATE_DIR, "AuditStatus"),
    );
  });

  it("AuditType — api.ts constant equals the Java enum constants (in order)", () => {
    expect([...AUDIT_TYPES]).toEqual(
      javaEnumConstantsIn(AUDIT_STATE_DIR, "AuditType"),
    );
  });
});
