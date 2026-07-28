// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Body is unchanged.

import {
  addResearchRunDisclosureEntry,
  addResearchRunRationaleEntry,
  addResearchRunReviewComment,
  advanceResearchRun,
  completeResearchRun,
  createResearchRunDisclosure,
  decideResearchRunGate,
  failResearchRun,
  getMethodologyRequirementsContract,
  getMethodologySelection,
  getProtocolPlan,
  getResearchRun,
  getResearchRunByUid,
  getResearchRunDisclosure,
  getResearchRunSnapshot,
  listMethodologyCatalog,
  listMethodologySources,
  listResearchRunArtifacts,
  listResearchRunGateDecisionLog,
  listResearchRunGates,
  listResearchRunRationale,
  listResearchRunReviewComments,
  listResearchRuns,
  pick,
  recordMethodologyRequirementsContract,
  recordMethodologySource,
  recordProtocolPlan,
  recordResearchRunArtifact,
  recordResearchRunUsage,
  reqArg,
  resolveResearchRunReviewComment,
  resumeResearchRun,
  selectMethodology,
  startResearchRun,
  stopResearchRun,
  updateMethodologySourceState,
} from "../lib.js";
import { ok, err } from "./respond.js";

export const researchRunHandler = async (args) => {
    try {
      const ARTIFACT_FIELDS = [
        "artifact_type", "locator", "content_hash", "idempotency_key",
        "candidate_sources", "screened_included", "screened_excluded",
        "charted_full_text", "access_gaps",
      ];
      const GATE_FIELDS = [
        "gate_point", "outcome", "selected_option_id", "rationale_summary",
        "recommendation_option_id", "recommendation_summary",
        "recommendation_provenance", "question_key", "source_action_id",
      ];
      const FAIL_FIELDS = ["error_code", "error_class", "error_summary"];
      switch (args.action) {
        case "start": {
          reqArg(args, "uid", "start");
          const body = { uid: args.uid };
          if (args.autonomy_level !== undefined) body.autonomyLevel = args.autonomy_level;
          if (args.intended_output !== undefined) body.intendedOutput = args.intended_output;
          // gateOverrides keys are gate-point enum constants — pass through as a
          // pre-built camelCase body so toCamelCase never rewrites the map keys.
          if (args.gate_overrides !== undefined) body.gateOverrides = args.gate_overrides;
          return ok(JSON.stringify(await startResearchRun(body, args.project), null, 2));
        }
        case "list":
          return ok(JSON.stringify(await listResearchRuns(args.project), null, 2));
        case "get": {
          reqArg(args, "id", "get");
          return ok(JSON.stringify(await getResearchRun(args.id, args.project), null, 2));
        }
        case "get_by_uid": {
          reqArg(args, "uid", "get_by_uid");
          return ok(JSON.stringify(await getResearchRunByUid(args.uid, args.project), null, 2));
        }
        case "snapshot": {
          reqArg(args, "id", "snapshot");
          return ok(JSON.stringify(await getResearchRunSnapshot(args.id, args.project), null, 2));
        }
        case "list_artifacts": {
          reqArg(args, "id", "list_artifacts");
          return ok(JSON.stringify(await listResearchRunArtifacts(args.id, args.project), null, 2));
        }
        case "list_gates": {
          reqArg(args, "id", "list_gates");
          return ok(JSON.stringify(await listResearchRunGates(args.id, args.project), null, 2));
        }
        case "record_artifact": {
          reqArg(args, "id", "record_artifact");
          reqArg(args, "artifact_type", "record_artifact");
          return ok(JSON.stringify(
            await recordResearchRunArtifact(args.id, pick(args, ARTIFACT_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "advance": {
          reqArg(args, "id", "advance");
          reqArg(args, "target_stage", "advance");
          return ok(JSON.stringify(
            await advanceResearchRun(args.id, { targetStage: args.target_stage }, args.project),
            null,
            2,
          ));
        }
        case "gate_decision": {
          reqArg(args, "id", "gate_decision");
          reqArg(args, "gate_point", "gate_decision");
          reqArg(args, "outcome", "gate_decision");
          return ok(JSON.stringify(
            await decideResearchRunGate(args.id, pick(args, GATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        // GC-RSCH-F004 / ADR-066 — gate decision audit log
        case "list_gate_decision_log": {
          reqArg(args, "id", "list_gate_decision_log");
          return ok(JSON.stringify(
            await listResearchRunGateDecisionLog(args.id, args.project),
            null,
            2,
          ));
        }
        // GC-RSCH-F034 / ADR-067 — run-scoped review comments
        case "add_review_comment": {
          reqArg(args, "id", "add_review_comment");
          reqArg(args, "target_type", "add_review_comment");
          reqArg(args, "body", "add_review_comment");
          reqArg(args, "provenance", "add_review_comment");
          const rcBody = { targetType: args.target_type, body: args.body, provenance: args.provenance };
          if (args.target_gate_point !== undefined) rcBody.targetGatePoint = args.target_gate_point;
          if (args.target_stage !== undefined) rcBody.targetStage = args.target_stage;
          if (args.target_artifact_id !== undefined) rcBody.targetArtifactId = args.target_artifact_id;
          if (args.target_decision_log_id !== undefined) rcBody.targetDecisionLogId = args.target_decision_log_id;
          return ok(JSON.stringify(
            await addResearchRunReviewComment(args.id, rcBody, args.project),
            null,
            2,
          ));
        }
        case "list_review_comments": {
          reqArg(args, "id", "list_review_comments");
          return ok(JSON.stringify(
            await listResearchRunReviewComments(args.id, args.project),
            null,
            2,
          ));
        }
        case "resolve_review_comment": {
          reqArg(args, "id", "resolve_review_comment");
          reqArg(args, "comment_id", "resolve_review_comment");
          const resolveBody = {};
          if (args.resolution_summary !== undefined) resolveBody.resolutionSummary = args.resolution_summary;
          return ok(JSON.stringify(
            await resolveResearchRunReviewComment(args.id, args.comment_id, resolveBody, args.project),
            null,
            2,
          ));
        }
        // GC-RSCH-N012 / ADR-068 — explainability / rationale ledger
        case "add_rationale": {
          reqArg(args, "id", "add_rationale");
          reqArg(args, "stage", "add_rationale");
          reqArg(args, "kind", "add_rationale");
          reqArg(args, "evidence_basis", "add_rationale");
          reqArg(args, "rationale_provenance", "add_rationale");
          reqArg(args, "subject_key", "add_rationale");
          reqArg(args, "rationale_summary", "add_rationale");
          const rBody = {
            stage: args.stage,
            kind: args.kind,
            evidenceBasis: args.evidence_basis,
            provenance: args.rationale_provenance,
            subjectKey: args.subject_key,
            rationaleSummary: args.rationale_summary,
          };
          if (args.artifact_type !== undefined) rBody.artifactType = args.artifact_type;
          if (args.target_artifact_id !== undefined) rBody.artifactId = args.target_artifact_id;
          if (args.attempt_no !== undefined) rBody.attemptNo = args.attempt_no;
          if (args.evidence_locator !== undefined) rBody.evidenceLocator = args.evidence_locator;
          if (args.confidence_summary !== undefined) rBody.confidenceSummary = args.confidence_summary;
          if (args.gate_point !== undefined) rBody.gatePoint = args.gate_point;
          return ok(JSON.stringify(
            await addResearchRunRationaleEntry(args.id, rBody, args.project),
            null,
            2,
          ));
        }
        case "list_rationale": {
          reqArg(args, "id", "list_rationale");
          return ok(JSON.stringify(
            await listResearchRunRationale(args.id, args.project),
            null,
            2,
          ));
        }
        // GC-RSCH-N013 / ADR-068 §4 — accountability disclosure
        case "create_disclosure": {
          reqArg(args, "id", "create_disclosure");
          reqArg(args, "final_artifact_id", "create_disclosure");
          reqArg(args, "final_attempt_no", "create_disclosure");
          const dBody = {
            finalArtifactId: args.final_artifact_id,
            finalAttemptNo: args.final_attempt_no,
            aiPartsDeclaredNone: args.ai_parts_declared_none ?? false,
            uncertaintyDeclaredNone: args.uncertainty_declared_none ?? false,
            humanApprovalsDeclaredNone: args.human_approvals_declared_none ?? false,
          };
          return ok(JSON.stringify(
            await createResearchRunDisclosure(args.id, dBody, args.project),
            null,
            2,
          ));
        }
        case "get_disclosure": {
          reqArg(args, "id", "get_disclosure");
          return ok(JSON.stringify(
            await getResearchRunDisclosure(args.id, args.project),
            null,
            2,
          ));
        }
        case "add_disclosure_entry": {
          reqArg(args, "id", "add_disclosure_entry");
          reqArg(args, "disclosure_id", "add_disclosure_entry");
          reqArg(args, "family", "add_disclosure_entry");
          reqArg(args, "summary", "add_disclosure_entry");
          const deBody = { family: args.family, summary: args.summary };
          if (args.uncertainty_category !== undefined) deBody.uncertaintyCategory = args.uncertainty_category;
          if (args.section_key !== undefined) deBody.sectionKey = args.section_key;
          if (args.locator !== undefined) deBody.locator = args.locator;
          if (args.model_label !== undefined) deBody.modelLabel = args.model_label;
          if (args.rationale_entry_id !== undefined) deBody.rationaleEntryId = args.rationale_entry_id;
          if (args.decision_log_id !== undefined) deBody.decisionLogId = args.decision_log_id;
          if (args.review_comment_id !== undefined) deBody.reviewCommentId = args.review_comment_id;
          return ok(JSON.stringify(
            await addResearchRunDisclosureEntry(args.id, args.disclosure_id, deBody, args.project),
            null,
            2,
          ));
        }
        case "stop": {
          reqArg(args, "id", "stop");
          return ok(JSON.stringify(await stopResearchRun(args.id, args.project), null, 2));
        }
        case "fail": {
          reqArg(args, "id", "fail");
          return ok(JSON.stringify(
            await failResearchRun(args.id, pick(args, FAIL_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "resume": {
          reqArg(args, "id", "resume");
          return ok(JSON.stringify(await resumeResearchRun(args.id, args.project), null, 2));
        }
        case "complete": {
          reqArg(args, "id", "complete");
          return ok(JSON.stringify(await completeResearchRun(args.id, args.project), null, 2));
        }
        case "record_usage": {
          reqArg(args, "id", "record_usage");
          reqArg(args, "tokens", "record_usage");
          reqArg(args, "cost_usd_micros", "record_usage");
          return ok(JSON.stringify(
            await recordResearchRunUsage(
              args.id,
              { tokens: args.tokens, costUsdMicros: args.cost_usd_micros },
              args.project,
            ),
            null,
            2,
          ));
        }
        // GC-RSCH-F006 / ADR-078 — methodology catalog (global reference data)
        case "list_methodology_catalog": {
          return ok(JSON.stringify(await listMethodologyCatalog(), null, 2));
        }
        // GC-RSCH-F006 — methodology selection + source coverage gate
        case "select_methodology": {
          reqArg(args, "id", "select_methodology");
          reqArg(args, "method_key", "select_methodology");
          // method label, profile/catalog version, and the required-source set are
          // derived server-side from the backend methodology catalog (ADR-078).
          const smBody = { methodKey: args.method_key };
          return ok(JSON.stringify(await selectMethodology(args.id, smBody, args.project), null, 2));
        }
        case "get_methodology_selection": {
          reqArg(args, "id", "get_methodology_selection");
          return ok(JSON.stringify(await getMethodologySelection(args.id, args.project), null, 2));
        }
        case "record_methodology_source": {
          reqArg(args, "id", "record_methodology_source");
          reqArg(args, "source_ref", "record_methodology_source");
          const rmsBody = { sourceRef: args.source_ref };
          if (args.source_label !== undefined) rmsBody.sourceLabel = args.source_label;
          return ok(JSON.stringify(await recordMethodologySource(args.id, rmsBody, args.project), null, 2));
        }
        case "update_methodology_source_state": {
          reqArg(args, "id", "update_methodology_source_state");
          reqArg(args, "source_id", "update_methodology_source_state");
          reqArg(args, "source_state", "update_methodology_source_state");
          return ok(JSON.stringify(
            await updateMethodologySourceState(
              args.id, args.source_id, { state: args.source_state }, args.project,
            ),
            null,
            2,
          ));
        }
        case "list_methodology_sources": {
          reqArg(args, "id", "list_methodology_sources");
          return ok(JSON.stringify(await listMethodologySources(args.id, args.project), null, 2));
        }
        case "record_methodology_requirements_contract": {
          reqArg(args, "id", "record_methodology_requirements_contract");
          reqArg(args, "entries", "record_methodology_requirements_contract");
          const contractBody = {
            entries: (args.entries || []).map((e) => ({
              kind: e.kind,
              entryKey: e.entry_key,
              statement: e.statement,
              sourceLinks: (e.source_links || []).map((s) => ({
                sourceId: s.source_id,
                locator: s.locator,
              })),
              referencesEntryKey: e.references_entry_key,
            })),
            rejectedAlternatives: (args.rejected_alternatives || []).map((r) => ({
              methodKey: r.method_key,
              profileVersion: r.profile_version,
              rationaleEntryId: r.rationale_entry_id,
              external: r.external ?? false,
            })),
          };
          return ok(JSON.stringify(
            await recordMethodologyRequirementsContract(args.id, contractBody, args.project),
            null,
            2,
          ));
        }
        case "get_methodology_requirements_contract": {
          reqArg(args, "id", "get_methodology_requirements_contract");
          return ok(JSON.stringify(
            await getMethodologyRequirementsContract(args.id, args.project),
            null,
            2,
          ));
        }
        case "record_protocol_plan": {
          reqArg(args, "id", "record_protocol_plan");
          reqArg(args, "protocol_schema_version", "record_protocol_plan");
          reqArg(args, "coverages", "record_protocol_plan");
          reqArg(args, "sections", "record_protocol_plan");
          const protocolPlanBody = {
            protocolSchemaVersion: args.protocol_schema_version,
            coverages: (args.coverages || []).map((c) => ({
              contractEntryKey: c.contract_entry_key,
              disposition: c.disposition,
              answerSummary: c.answer_summary,
              answerProvenance: c.answer_provenance,
              rationale: c.rationale,
              deferredToStage: c.deferred_to_stage,
              decisionReference: c.decision_reference,
            })),
            sections: (args.sections || []).map((s) => ({
              sectionKey: s.section_key,
              sectionKind: s.section_kind,
              sourceRole: s.source_role,
              contentSummary: s.content_summary,
            })),
          };
          return ok(JSON.stringify(
            await recordProtocolPlan(args.id, protocolPlanBody, args.project),
            null,
            2,
          ));
        }
        case "get_protocol_plan": {
          reqArg(args, "id", "get_protocol_plan");
          return ok(JSON.stringify(await getProtocolPlan(args.id, args.project), null, 2));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  };
