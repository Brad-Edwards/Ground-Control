// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  CONTRACT_ENTRY_KINDS,
  DISCLOSURE_ENTRY_FAMILIES,
  DISCLOSURE_UNCERTAINTY_CATEGORIES,
  GATE_RECOMMENDATION_PROVENANCES,
  METHODOLOGY_SOURCE_STATES,
  PROTOCOL_ANSWER_PROVENANCES,
  PROTOCOL_COVERAGE_DISPOSITIONS,
  PROTOCOL_SECTION_KINDS,
  PROTOCOL_SOURCE_ROLES,
  RATIONALE_ENTRY_KINDS,
  RATIONALE_EVIDENCE_BASES,
  RATIONALE_PROVENANCES,
  RESEARCH_ARTIFACT_TYPES,
  RESEARCH_GATE_BEHAVIORS,
  RESEARCH_GATE_DECISION_OUTCOMES,
  RESEARCH_GATE_POINTS,
  RESEARCH_RUN_AUTONOMY_LEVELS,
  RESEARCH_RUN_INTENDED_OUTPUTS,
  RESEARCH_RUN_STAGES,
  REVIEW_COMMENT_PROVENANCES,
  REVIEW_COMMENT_TARGETS,
} from "../lib.js";

export const RESEARCH_RUN_ACTIONS = [
  "start",
  "list",
  "get",
  "get_by_uid",
  "snapshot",
  "list_artifacts",
  "list_gates",
  "record_artifact",
  "advance",
  "gate_decision",
  "list_gate_decision_log",
  "add_review_comment",
  "list_review_comments",
  "resolve_review_comment",
  "add_rationale",
  "list_rationale",
  "create_disclosure",
  "get_disclosure",
  "add_disclosure_entry",
  "list_methodology_catalog",
  "select_methodology",
  "get_methodology_selection",
  "record_methodology_source",
  "update_methodology_source_state",
  "list_methodology_sources",
  "record_methodology_requirements_contract",
  "get_methodology_requirements_contract",
  "record_protocol_plan",
  "get_protocol_plan",
  "stop",
  "fail",
  "resume",
  "complete",
  "record_usage",
];

import { researchRunHandler } from "./research-run-handler.js";

export function registerResearchRun(server, ctx) {
  server.tool(
    "gc_research_run",
    `Research run lifecycle operations (GC-RSCH-R001/R003/F003/F006/F034/F036/N007/N011/N012/N013, ADR-064 / ADR-065 / ADR-066 / ADR-067 / ADR-068). ` +
      `Actions: ${RESEARCH_RUN_ACTIONS.join(", ")}. ` +
      `Reads (list, get, get_by_uid, snapshot, list_artifacts, list_gates, list_gate_decision_log, list_review_comments, list_rationale, get_disclosure, list_methodology_catalog, get_methodology_selection, list_methodology_sources, get_methodology_requirements_contract, get_protocol_plan) also route through gc_query. ` +
      `Required fields per action: start→{uid}; get/snapshot/list_artifacts/list_gates/stop/resume/complete→{id}; get_by_uid→{uid}; record_artifact→{id,artifact_type}; advance→{id,target_stage}; gate_decision→{id,gate_point,outcome}; list_gate_decision_log→{id}; add_review_comment→{id,target_type,body,provenance}; list_review_comments→{id}; resolve_review_comment→{id,comment_id}; add_rationale→{id,stage,kind,evidence_basis,provenance,subject_key,rationale_summary}; list_rationale→{id}; create_disclosure→{id,final_artifact_id,final_attempt_no}; get_disclosure→{id}; add_disclosure_entry→{id,disclosure_id,family,summary}; list_methodology_catalog→{} (global; no run id — lists every catalog method profile with its required primary sources); select_methodology→{id,method_key} (method label, profile/catalog version, and the required-source set are derived server-side from the backend methodology catalog and snapshotted as required=true rows); get_methodology_selection→{id}; record_methodology_source→{id,source_ref} (always optional/additional); update_methodology_source_state→{id,source_id,source_state}; list_methodology_sources→{id}; record_methodology_requirements_contract→{id,entries} (each entry {kind,entry_key,statement,source_links?,references_entry_key?}; REQUIREMENT/METHOD_LIMIT/NON_CLAIM need ≥1 READ source_link); get_methodology_requirements_contract→{id}; record_protocol_plan→{id,protocol_schema_version,coverages,sections} (method key, profile version, methodology contract id/attempt, and artifact attempt are resolved server-side; each coverage {contract_entry_key,disposition,answer_summary?,answer_provenance?,rationale?,deferred_to_stage?,decision_reference?}; each section {section_key,section_kind,source_role?,content_summary}); get_protocol_plan→{id}; fail→{id}; record_usage→{id,tokens,cost_usd_micros}. ` +
      `Bounded metadata only — never pass prompts, manuscript bodies, secrets, or absolute paths. Actor is always from server context (ADR-026).`,
    {
      action: z.enum(RESEARCH_RUN_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      uid: z.string().optional(),
      // start
      autonomy_level: z.enum(RESEARCH_RUN_AUTONOMY_LEVELS).optional(),
      intended_output: z.enum(RESEARCH_RUN_INTENDED_OUTPUTS).optional(),
      gate_overrides: z.record(z.enum(RESEARCH_GATE_BEHAVIORS)).optional(),
      // record_artifact
      artifact_type: z.enum(RESEARCH_ARTIFACT_TYPES).optional(),
      locator: z.string().optional(),
      content_hash: z.string().optional(),
      idempotency_key: z.string().optional(),
      candidate_sources: z.number().int().nonnegative().optional(),
      screened_included: z.number().int().nonnegative().optional(),
      screened_excluded: z.number().int().nonnegative().optional(),
      charted_full_text: z.number().int().nonnegative().optional(),
      access_gaps: z.number().int().nonnegative().optional(),
      // advance
      target_stage: z.enum(RESEARCH_RUN_STAGES).optional(),
      // gate_decision (incl. ADR-066 recommendation fields)
      gate_point: z.enum(RESEARCH_GATE_POINTS).optional(),
      outcome: z.enum(RESEARCH_GATE_DECISION_OUTCOMES).optional(),
      selected_option_id: z.string().optional(),
      rationale_summary: z.string().optional(),
      recommendation_option_id: z.string().optional(),
      recommendation_summary: z.string().optional(),
      recommendation_provenance: z.enum(GATE_RECOMMENDATION_PROVENANCES).optional(),
      question_key: z.string().optional(),
      source_action_id: z.string().optional(),
      // NOTE: actor/owner provenance is taken from the authenticated server
      // context (ActorHolder/ActorFilter, ADR-026); there is deliberately no
      // client-supplied actor field on any research-run write.
      // review comments (ADR-067)
      comment_id: z.string().uuid().optional(),
      target_type: z.enum(REVIEW_COMMENT_TARGETS).optional(),
      target_gate_point: z.enum(RESEARCH_GATE_POINTS).optional(),
      target_stage: z.enum(RESEARCH_RUN_STAGES).optional(),
      target_artifact_id: z.string().uuid().optional(),
      target_decision_log_id: z.string().uuid().optional(),
      body: z.string().optional(),
      provenance: z.enum(REVIEW_COMMENT_PROVENANCES).optional(),
      resolution_summary: z.string().optional(),
      // rationale entry (ADR-068)
      stage: z.enum(RESEARCH_RUN_STAGES).optional(),
      kind: z.enum(RATIONALE_ENTRY_KINDS).optional(),
      evidence_basis: z.enum(RATIONALE_EVIDENCE_BASES).optional(),
      rationale_provenance: z.enum(RATIONALE_PROVENANCES).optional(),
      subject_key: z.string().optional(),
      evidence_locator: z.string().optional(),
      confidence_summary: z.string().optional(),
      attempt_no: z.number().int().nonnegative().optional(),
      // disclosure (ADR-068 §4)
      disclosure_id: z.string().uuid().optional(),
      final_artifact_id: z.string().uuid().optional(),
      final_attempt_no: z.number().int().positive().optional(),
      ai_parts_declared_none: z.boolean().optional(),
      uncertainty_declared_none: z.boolean().optional(),
      human_approvals_declared_none: z.boolean().optional(),
      family: z.enum(DISCLOSURE_ENTRY_FAMILIES).optional(),
      uncertainty_category: z.enum(DISCLOSURE_UNCERTAINTY_CATEGORIES).optional(),
      section_key: z.string().optional(),
      model_label: z.string().optional(),
      summary: z.string().optional(),
      rationale_entry_id: z.string().uuid().optional(),
      decision_log_id: z.string().uuid().optional(),
      review_comment_id: z.string().uuid().optional(),
      // fail
      error_code: z.string().optional(),
      error_class: z.string().optional(),
      error_summary: z.string().optional(),
      // record_usage
      tokens: z.number().int().nonnegative().optional(),
      cost_usd_micros: z.number().int().nonnegative().optional(),
      // methodology selection (GC-RSCH-F006 / ADR-078). Only method_key is accepted;
      // the label, profile/catalog version, and required-source set are derived
      // server-side from the backend methodology catalog.
      method_key: z.string().optional(),
      // methodology sources (GC-RSCH-F006)
      source_ref: z.string().optional(),
      source_label: z.string().optional(),
      source_state: z.enum(METHODOLOGY_SOURCE_STATES).optional(),
      source_id: z.string().uuid().optional(),
      // methodology requirements contract (GC-RSCH-F007 / ADR-080). No domain-answer
      // fields; chosen method, artifact id, and attempt are resolved server-side.
      entries: z
        .array(
          z.object({
            kind: z.enum(CONTRACT_ENTRY_KINDS),
            entry_key: z.string(),
            statement: z.string(),
            source_links: z
              .array(z.object({ source_id: z.string().uuid(), locator: z.string().optional() }))
              .optional(),
            references_entry_key: z.string().optional(),
          }),
        )
        .optional(),
      rejected_alternatives: z
        .array(
          z.object({
            method_key: z.string(),
            profile_version: z.string().optional(),
            rationale_entry_id: z.string().uuid().optional(),
            external: z.boolean().optional(),
          }),
        )
        .optional(),
      // protocol plan (GC-RSCH-F008 / GC-RSCH-F009 / ADR-083). Method key, profile
      // version, methodology contract id/attempt, and artifact attempt are resolved
      // server-side from the run's active selection and active artifacts, never
      // passed here.
      protocol_schema_version: z.string().optional(),
      coverages: z
        .array(
          z.object({
            contract_entry_key: z.string(),
            disposition: z.enum(PROTOCOL_COVERAGE_DISPOSITIONS),
            answer_summary: z.string().optional(),
            answer_provenance: z.enum(PROTOCOL_ANSWER_PROVENANCES).optional(),
            rationale: z.string().optional(),
            deferred_to_stage: z.enum(RESEARCH_RUN_STAGES).optional(),
            decision_reference: z.string().optional(),
          }),
        )
        .optional(),
      sections: z
        .array(
          z.object({
            section_key: z.string(),
            section_kind: z.enum(PROTOCOL_SECTION_KINDS),
            source_role: z.enum(PROTOCOL_SOURCE_ROLES).optional(),
            content_summary: z.string(),
          }),
        )
        .optional(),
    },
    researchRunHandler,
  );
}
