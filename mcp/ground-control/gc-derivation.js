// gc_derivation: action-discriminated MCP adapter for the GC-GRC-001
// derivation REST surface. Invokes server-side derivation and reads persisted
// normalized system-model facts plus capture limits.

import { z } from "zod";
import {
  CAPTURE_LIMIT_REASONS,
  DERIVATION_SCOPE_MODES,
  SYSTEM_MODEL_FACT_KINDS,
  getDerivationBoundaryModel,
  getDerivationRun,
  getRepoGroundControlContext,
  listDerivationCaptureLimits,
  listDerivationFacts,
  listDerivationRuns,
  pick,
  reqArg,
  runDerivation,
} from "./lib.js";

export const GC_DERIVATION_ACTIONS = [
  "run",
  "list_runs",
  "get_run",
  "get_boundary_model",
  "list_facts",
  "list_capture_limits",
];

export const GC_DERIVATION_RUN_FIELDS = [
  "scope_mode",
  "commit_sha",
  "base_commit_sha",
  "paths",
  "languages",
  "surfaces",
  "declared_boundaries",
];

const declaredBoundarySchema = z.object({
  key: z.string().min(1).max(120),
  name: z.string().min(1).max(200),
  description: z.string().min(1).max(2000).optional(),
  path_selectors: z.array(z.string().min(1).max(500)).optional(),
  paths: z.array(z.string().min(1).max(500)).optional(),
  surfaces: z.array(z.string().min(1).max(80)).optional(),
}).refine((value) => (value.path_selectors ?? value.paths ?? []).length > 0, {
  message: "path_selectors or paths must include at least one selector",
});

export const gcDerivationZodShape = {
  action: z.enum(GC_DERIVATION_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  repo_path: z.string().min(1).optional(),
  run_id: z.string().uuid().optional(),
  scope_mode: z.enum(DERIVATION_SCOPE_MODES).optional(),
  commit_sha: z.string().regex(/^[0-9a-fA-F]{7,64}$/).optional(),
  base_commit_sha: z.string().regex(/^[0-9a-fA-F]{7,64}$/).optional(),
  paths: z.array(z.string().min(1).max(500)).optional(),
  languages: z.array(z.string().min(1).max(80)).optional(),
  surfaces: z.array(z.string().min(1).max(80)).optional(),
  declared_boundaries: z.array(declaredBoundarySchema).max(100).optional(),
  fact_kind: z.enum(SYSTEM_MODEL_FACT_KINDS).optional(),
  reason: z.enum(CAPTURE_LIMIT_REASONS).optional(),
};

export const GC_DERIVATION_DESCRIPTION =
  `Derivation operations (GC-GRC-001). Actions: ${GC_DERIVATION_ACTIONS.join(", ")}. ` +
  `run invokes server-side derivation for a repository scope and persists normalized facts; ` +
  `requires scope_mode, commit_sha, languages, and surfaces. run accepts declared_boundaries, ` +
  `or repo_path to read grc.boundaries from .ground-control.yaml. ` +
  `get_boundary_model reads the canonical boundary snapshot for a run. ` +
  `list_facts accepts optional run_id and fact_kind. ` +
  `list_capture_limits accepts optional run_id and reason.`;

export async function gcDerivationToolHandler(args) {
  switch (args.action) {
    case "run": {
      reqArg(args, "scope_mode", "run");
      reqArg(args, "commit_sha", "run");
      reqArg(args, "languages", "run");
      reqArg(args, "surfaces", "run");
      const { project, declaredBoundaries } = await resolveRunContext(args);
      return runDerivation(toRunBody({ ...args, declared_boundaries: declaredBoundaries }), project);
    }
    case "list_runs":
      return listDerivationRuns({ project: args.project });
    case "get_run":
      reqArg(args, "id", "get_run");
      return getDerivationRun(args.id, args.project);
    case "get_boundary_model":
      reqArg(args, "id", "get_boundary_model");
      return getDerivationBoundaryModel(args.id, args.project);
    case "list_facts":
      return listDerivationFacts({
        project: args.project,
        runId: args.run_id,
        factKind: args.fact_kind,
      });
    case "list_capture_limits":
      return listDerivationCaptureLimits({
        project: args.project,
        runId: args.run_id,
        reason: args.reason,
      });
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

async function resolveRunContext(args) {
  if (args.declared_boundaries != null || args.repo_path == null) {
    return {
      project: args.project,
      declaredBoundaries: args.declared_boundaries,
    };
  }
  const context = await getRepoGroundControlContext(args.repo_path);
  if (context.status !== "ok") {
    throw new Error(`Invalid repo context for derivation run: ${(context.errors || []).join("; ") || context.status}`);
  }
  return {
    project: args.project ?? context.project,
    declaredBoundaries: context.grc?.boundaries ?? [],
  };
}

function toRunBody(args) {
  const body = pick(args, GC_DERIVATION_RUN_FIELDS);
  const result = {
    scopeMode: body.scope_mode,
    commitSha: body.commit_sha,
    baseCommitSha: body.base_commit_sha,
    paths: body.paths,
    languages: body.languages,
    surfaces: body.surfaces,
  };
  const declaredBoundaries = normalizeDeclaredBoundaries(body.declared_boundaries);
  if (declaredBoundaries.length > 0) {
    result.declaredBoundaries = declaredBoundaries;
  }
  return result;
}

function normalizeDeclaredBoundaries(boundaries) {
  if (!Array.isArray(boundaries)) return [];
  return boundaries.map((boundary) => ({
    key: boundary.key,
    name: boundary.name,
    description: boundary.description ?? null,
    pathSelectors: boundary.path_selectors ?? boundary.paths ?? boundary.pathSelectors ?? [],
    surfaces: boundary.surfaces ?? [],
  }));
}
