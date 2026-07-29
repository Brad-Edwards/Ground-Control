// Durable ADR-036 step-observation builder (ADR-090 amendment, issue #1354).
//
// Split out of repo-vocabulary.js for the 500-LOC limit (docs/CODING_STANDARDS.md, Sonar S104).
// This is the successor to the forward JSONL record: the same step/tier/model/wall-time/outcome,
// mapped onto the ADR-061 phase-event write path instead of a gitignored file. It reuses the
// provider-neutral tier vocabulary and the tier→model table that stay in repo-vocabulary.js.

import { CLAUDE_MODEL_BY_TIER, TELEMETRY_OUTCOMES, TELEMETRY_TIERS } from "./repo-vocabulary.js";

// The versioned measurement contract (gc.measurement.record.v1) a durable step observation is
// produced against, and the emitter value that distinguishes it from a lifecycle/station row on the
// ADR-061 phase-event table.
export const MEASUREMENT_VERSION = "gc.measurement/v1";
export const STEP_TELEMETRY_EMITTER = "ADR036_STEP_JSONL";

/**
 * Build the durable ADR-036 step-observation event body for the ADR-061 phase-event write path.
 *
 * The keys are snake_case to match the REST contract; `tier` is upper-cased to the backend
 * CapabilityTier vocabulary; `source_id` is namespaced to the ADR-036 emitter so it can never
 * collide with a live station attempt's `phase:eventType:cycleIndex` identity. `phase` carries the
 * stable ADR-036 stage id — the backend resolves the catalogue station from it, so no station id is
 * sent — and the row states operation outcome only, never a station verdict.
 */
export function buildStepObservationEvent(input) {
  if (input == null || typeof input !== "object") {
    throw new Error("buildStepObservationEvent: input must be an object");
  }
  const errors = [];
  const { stage, step, tier, model, wallTimeMs, inputTokens = null, outputTokens = null, outcome, attempt, ts } = input;
  if (typeof stage !== "string" || stage.trim() === "") errors.push("stage must be non-empty string");
  if (!TELEMETRY_TIERS.includes(tier)) errors.push(`tier must be one of: ${TELEMETRY_TIERS.join(", ")}`);
  if (typeof model !== "string" || model.trim() === "") errors.push("model must be non-empty string");
  if (!Number.isInteger(wallTimeMs) || wallTimeMs < 0) errors.push("wallTimeMs must be non-negative integer");
  if (!Number.isInteger(attempt) || attempt < 0) errors.push("attempt must be non-negative integer");
  if (inputTokens != null && (!Number.isInteger(inputTokens) || inputTokens < 0)) errors.push("inputTokens must be non-negative integer or null");
  if (outputTokens != null && (!Number.isInteger(outputTokens) || outputTokens < 0)) errors.push("outputTokens must be non-negative integer or null");
  if (!TELEMETRY_OUTCOMES.includes(outcome)) errors.push(`outcome must be one of: ${TELEMETRY_OUTCOMES.join(", ")}`);
  if (step != null && (typeof step !== "string" || step.trim() === "")) errors.push("step must be non-empty string or null");
  if (ts != null && (typeof ts !== "string" || ts.trim() === "")) errors.push("ts must be non-empty ISO-8601 string or null");
  if (errors.length) {
    throw new Error(`buildStepObservationEvent input invalid: ${errors.join("; ")}`);
  }
  const expectedModel = CLAUDE_MODEL_BY_TIER[tier];
  return {
    phase: stage,
    event_type: "COMPLETED",
    occurred_at: ts ?? new Date().toISOString(),
    duration_ms: wallTimeMs,
    outcome,
    provenance: "LIVE_EMISSION",
    cycle_index: attempt,
    source_id: `adr036_step:${stage}:${attempt}`,
    emitter: STEP_TELEMETRY_EMITTER,
    measurement_version: MEASUREMENT_VERSION,
    step_alias: step ?? null,
    tier: tier.toUpperCase(),
    model,
    expected_model: expectedModel,
    model_matches_expected: model === expectedModel,
    input_tokens: inputTokens,
    output_tokens: outputTokens,
  };
}
