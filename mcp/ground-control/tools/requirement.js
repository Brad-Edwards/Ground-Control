// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  ADR_STATUSES,
  CONFIDENCE_LEVELS,
  LINK_TYPES,
  PRIORITIES,
  RELATION_TYPES,
  REQUIREMENT_TYPES,
  STATUSES,
  addSectionContent,
  analyzeCompleteness,
  analyzeSemanticSimilarity,
  analyzeStatusDrift,
  archiveRequirement,
  cloneRequirement,
  createAdr,
  createDocument,
  createRelation,
  createRequirement,
  createSection,
  crossWaveValidation,
  deleteAdr,
  deleteDocument,
  deleteDocumentGrammar,
  deleteRelation,
  deleteSection,
  deleteSectionContent,
  detectConsistencyViolations,
  detectCycles,
  extractSubgraph,
  findCoverageGaps,
  findGraphPaths,
  findOrphans,
  findPaths,
  getAdrRequirements,
  getAncestors,
  getDescendants,
  getDocumentReadingOrder,
  getGraphVisualization,
  getRelations,
  getRequirementByUid,
  getSectionTree,
  getWorkOrder,
  impactAnalysis,
  listRequirements,
  pick,
  reqArg,
  setDocumentGrammar,
  transitionAdrStatus,
  traverseGraph,
  updateAdr,
  updateDocument,
  updateRequirement,
  updateSection,
  updateSectionContent,
} from "../lib.js";
import { ok, err } from "./respond.js";

export const REQUIREMENT_ACTIONS = ["list", "create", "update", "delete", "archive", "clone"];

export const RELATION_ACTIONS = ["create", "get", "delete"];

export const ADR_ACTIONS = ["create", "update", "delete", "transition", "requirements"];

export const DOCUMENT_ACTIONS = ["create", "update", "delete", "grammar_set", "grammar_delete", "reading_order"];

export const SECTION_ACTIONS = ["create", "update", "delete", "tree", "content_add", "content_update", "content_delete"];

export const ANALYZE_KINDS = [
  "cycles", "orphans", "coverage_gaps", "impact", "cross_wave",
  "consistency", "completeness", "status_drift", "similarity", "work_order",
];

export const GRAPH_MODES =["ancestors", "descendants", "paths", "subgraph", "visualization", "traverse", "find_paths"];


export function registerRequirement(server, ctx) {
  server.tool(
    "gc_requirement",
    `Requirement operations (action-discriminated). Actions: ${REQUIREMENT_ACTIONS.join(", ")}. ` +
      `Reads (list/get/history/diff/timeline) route through gc_query against /api/v1/requirements; the history and timeline GETs accept an optional expand=true query param (pass it via gc_query params) to return full field values, since string change values over 200 chars are truncated by default with a truncated flag. ` +
      `Status transitions live on gc_transition_status / gc_bulk_transition_status (workflow primitives). ` +
      `Required fields per action: create→{uid|uid_prefix,title,statement}; update→{id}; delete/archive→{id}; clone→{source_uid,new_uid}.`,
    {
      action: z.enum(REQUIREMENT_ACTIONS),
      // identifiers
      id: z.string().uuid().optional(),
      uid: z.string().optional(),
      uid_prefix: z.string().optional(),
      source_uid: z.string().optional(),
      new_uid: z.string().optional(),
      // create/update fields
      project: z.string().optional(),
      title: z.string().optional(),
      statement: z.string().optional(),
      rationale: z.string().optional(),
      requirement_type: z.enum(REQUIREMENT_TYPES).optional(),
      priority: z.enum(PRIORITIES).optional(),
      wave: z.number().int().optional(),
      status: z.enum(STATUSES).optional(),
      // list filtering
      type: z.enum(REQUIREMENT_TYPES).optional(),
      search: z.string().optional(),
      page: z.number().int().optional(),
      size: z.number().int().optional(),
      sort: z.string().optional(),
      // clone
      copy_relations: z.boolean().optional(),
    },
    async (args) => {
      try {
        const ENTITY_FIELDS = ["uid", "uid_prefix", "title", "statement", "rationale", "requirement_type", "priority", "wave", "status"];
        switch (args.action) {
          case "list": {
            const filter = pick(args, ["status", "type", "priority", "wave", "search", "page", "size", "sort", "project"]);
            return ok(JSON.stringify(await listRequirements(filter), null, 2));
          }
          case "create": {
            const hasUid = args.uid != null && String(args.uid).trim() !== "";
            const hasPrefix = args.uid_prefix != null && String(args.uid_prefix).trim() !== "";
            if (hasUid === hasPrefix) {
              throw new Error("create: exactly one of uid or uid_prefix must be provided (non-blank)");
            }
            reqArg(args, "title", "create"); reqArg(args, "statement", "create");
            return ok(JSON.stringify(await createRequirement(pick(args, ENTITY_FIELDS), args.project), null, 2));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(await updateRequirement(args.id, pick(args, ENTITY_FIELDS)), null, 2));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await archiveRequirement(args.id);
            return ok("Archived (the backend has no hard delete for requirements; transitioned to ARCHIVED)");
          }
          case "archive": {
            reqArg(args, "id", "archive");
            return ok(JSON.stringify(await archiveRequirement(args.id), null, 2));
          }
          case "clone": {
            // Note: lib.js cloneRequirement signature is (id, newUid, copyRelations).
            // The `id` is the SOURCE requirement's UUID. Look it up from source_uid
            // if the caller only knows the human-readable UID.
            reqArg(args, "new_uid", "clone");
            let sourceId = args.id;
            if (!sourceId) {
              reqArg(args, "source_uid", "clone");
              const src = await getRequirementByUid(args.source_uid, args.project);
              sourceId = src?.id;
              if (!sourceId) throw new Error(`clone: source requirement '${args.source_uid}' not found`);
            }
            return ok(JSON.stringify(await cloneRequirement(sourceId, args.new_uid, args.copy_relations ?? false), null, 2));
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_relation",
    `Requirement-to-requirement relations. Actions: ${RELATION_ACTIONS.join(", ")}. ` +
      `Reads (history) route through gc_query. ` +
      `Required fields per action: create→{source_id,target_id,relation_type}; get→{requirement_id}; delete→{requirement_id,id}.`,
    {
      action: z.enum(RELATION_ACTIONS),
      id: z.string().uuid().optional(),
      requirement_id: z.string().uuid().optional(),
      source_id: z.string().uuid().optional(),
      target_id: z.string().uuid().optional(),
      relation_type: z.enum(RELATION_TYPES).optional(),
    },
    async (args) => {
      try {
        switch (args.action) {
          case "create": {
            reqArg(args, "source_id", "create"); reqArg(args, "target_id", "create"); reqArg(args, "relation_type", "create");
            return ok(JSON.stringify(await createRelation(args.source_id, args.target_id, args.relation_type), null, 2));
          }
          case "get": {
            reqArg(args, "requirement_id", "get");
            return ok(JSON.stringify(await getRelations(args.requirement_id), null, 2));
          }
          case "delete": {
            // lib.js signature: deleteRelation(reqId, relId)
            reqArg(args, "requirement_id", "delete"); reqArg(args, "id", "delete");
            await deleteRelation(args.requirement_id, args.id);
            return ok("Deleted");
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_adr",
    `ADR operations. Actions: ${ADR_ACTIONS.join(", ")}. ` +
      `Reads (list, get) route through gc_query. ` +
      `Required fields per action: create→{uid,title}; update/delete/requirements→{id}; transition→{id,status}.`,
    {
      action: z.enum(ADR_ACTIONS),
      id: z.string().uuid().optional(),
      uid: z.string().optional(),
      project: z.string().optional(),
      title: z.string().optional(),
      status: z.enum(ADR_STATUSES).optional(),
      decision_date: z.string().optional(),
      context: z.string().optional(),
      decision: z.string().optional(),
      consequences: z.string().optional(),
      superseded_by: z.string().uuid().nullable().optional(),
    },
    async (args) => {
      try {
        const ENTITY_FIELDS = ["uid", "title", "status", "decision_date", "context", "decision", "consequences", "superseded_by"];
        switch (args.action) {
          case "create": {
            reqArg(args, "uid", "create"); reqArg(args, "title", "create");
            return ok(JSON.stringify(await createAdr(pick(args, ENTITY_FIELDS), args.project), null, 2));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(await updateAdr(args.id, pick(args, ENTITY_FIELDS)), null, 2));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteAdr(args.id);
            return ok("Deleted");
          }
          case "transition": {
            // lib.js signature: transitionAdrStatus(id, status). superseded_by lands via update.
            reqArg(args, "id", "transition"); reqArg(args, "status", "transition");
            return ok(JSON.stringify(await transitionAdrStatus(args.id, args.status), null, 2));
          }
          case "requirements": {
            reqArg(args, "id", "requirements");
            return ok(JSON.stringify(await getAdrRequirements(args.id), null, 2));
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_document",
    `Document operations + grammar + reading-order. Actions: ${DOCUMENT_ACTIONS.join(", ")}. ` +
      `Reads (list, get, grammar_get) route through gc_query. ` +
      `Required fields per action: create→{title}; update/delete/grammar_delete/reading_order→{id}; grammar_set→{id,grammar}.`,
    {
      action: z.enum(DOCUMENT_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      title: z.string().optional(),
      description: z.string().optional(),
      grammar: z.record(z.any()).optional(),
    },
    async (args) => {
      try {
        const ENTITY_FIELDS = ["title", "description"];
        switch (args.action) {
          case "create": {
            reqArg(args, "title", "create");
            return ok(JSON.stringify(await createDocument(pick(args, ENTITY_FIELDS), args.project), null, 2));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(await updateDocument(args.id, pick(args, ENTITY_FIELDS)), null, 2));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteDocument(args.id);
            return ok("Deleted");
          }
          case "grammar_set": {
            reqArg(args, "id", "grammar_set"); reqArg(args, "grammar", "grammar_set");
            return ok(JSON.stringify(await setDocumentGrammar(args.id, args.grammar), null, 2));
          }
          case "grammar_delete": {
            reqArg(args, "id", "grammar_delete");
            await deleteDocumentGrammar(args.id);
            return ok("Grammar deleted");
          }
          case "reading_order": {
            reqArg(args, "id", "reading_order");
            return ok(JSON.stringify(await getDocumentReadingOrder(args.id), null, 2));
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_section",
    `Section + section-content operations. Actions: ${SECTION_ACTIONS.join(", ")}. ` +
      `Reads (list, get, content_list) route through gc_query. ` +
      `Required fields per action: create→{document_id,title}; update/delete→{id}; tree→{document_id}; content_add→{id,content_type}; content_update/content_delete→{content_id}.`,
    {
      action: z.enum(SECTION_ACTIONS),
      id: z.string().uuid().optional(),
      document_id: z.string().uuid().optional(),
      parent_section_id: z.string().uuid().nullable().optional(),
      title: z.string().optional(),
      description: z.string().optional(),
      ordinal: z.number().int().optional(),
      content_id: z.string().uuid().optional(),
      content_type: z.string().optional(),
      requirement_id: z.string().uuid().optional(),
      text: z.string().optional(),
      project: z.string().optional(),
    },
    async (args) => {
      try {
        const SECTION_ENTITY_FIELDS = ["parent_section_id", "title", "description", "ordinal"];
        const CONTENT_ENTITY_FIELDS = ["content_type", "requirement_id", "text", "ordinal"];
        switch (args.action) {
          case "create": {
            reqArg(args, "document_id", "create"); reqArg(args, "title", "create");
            return ok(JSON.stringify(await createSection(args.document_id, pick(args, SECTION_ENTITY_FIELDS)), null, 2));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(await updateSection(args.id, pick(args, SECTION_ENTITY_FIELDS)), null, 2));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteSection(args.id);
            return ok("Deleted");
          }
          case "tree": {
            reqArg(args, "document_id", "tree");
            return ok(JSON.stringify(await getSectionTree(args.document_id), null, 2));
          }
          case "content_add": {
            reqArg(args, "id", "content_add"); reqArg(args, "content_type", "content_add");
            return ok(JSON.stringify(await addSectionContent(args.id, pick(args, CONTENT_ENTITY_FIELDS)), null, 2));
          }
          case "content_update": {
            reqArg(args, "content_id", "content_update");
            return ok(JSON.stringify(await updateSectionContent(args.content_id, pick(args, CONTENT_ENTITY_FIELDS)), null, 2));
          }
          case "content_delete": {
            reqArg(args, "content_id", "content_delete");
            await deleteSectionContent(args.content_id);
            return ok("Content deleted");
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_analyze",
    `Compute-heavy analysis operations. Kinds: ${ANALYZE_KINDS.join(", ")}. ` +
      `Required fields per kind: coverage_gaps→{link_type}; impact→{id}; status_drift→{minimum_confidence?}; similarity→{threshold?}; ` +
      `Others take {project?}.`,
    {
      kind: z.enum(ANALYZE_KINDS),
      project: z.string().optional(),
      id: z.string().uuid().optional(),
      link_type: z.enum(LINK_TYPES).optional(),
      minimum_confidence: z.enum(CONFIDENCE_LEVELS).optional(),
      threshold: z.number().optional(),
    },
    async (args) => {
      try {
        switch (args.kind) {
          case "cycles": return ok(JSON.stringify(await detectCycles(args.project), null, 2));
          case "orphans": return ok(JSON.stringify(await findOrphans(args.project), null, 2));
          case "coverage_gaps": {
            reqArg(args, "link_type", "coverage_gaps");
            return ok(JSON.stringify(await findCoverageGaps(args.link_type, args.project), null, 2));
          }
          case "impact": {
            reqArg(args, "id", "impact");
            return ok(JSON.stringify(await impactAnalysis(args.id), null, 2));
          }
          case "cross_wave": return ok(JSON.stringify(await crossWaveValidation(args.project), null, 2));
          case "consistency": return ok(JSON.stringify(await detectConsistencyViolations(args.project), null, 2));
          case "completeness": return ok(JSON.stringify(await analyzeCompleteness(args.project), null, 2));
          case "status_drift": return ok(JSON.stringify(await analyzeStatusDrift({ project: args.project, minimumConfidence: args.minimum_confidence }), null, 2));
          case "similarity": return ok(JSON.stringify(await analyzeSemanticSimilarity({ project: args.project, threshold: args.threshold }), null, 2));
          case "work_order": return ok(JSON.stringify(await getWorkOrder(args.project), null, 2));
          default: return err(new Error(`Unknown kind: ${args.kind}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_graph",
    `Graph traversal. Modes: ${GRAPH_MODES.join(", ")}. ` +
      `Required: ancestors/descendants→{uid}; paths/find_paths→{source,target}; subgraph/traverse→{roots}; visualization→{project?}. entity_types/max_depth are optional refinements.`,
    {
      mode: z.enum(GRAPH_MODES),
      project: z.string().optional(),
      uid: z.string().optional(),
      source: z.string().optional(),
      target: z.string().optional(),
      roots: z.array(z.string()).optional(),
      depth: z.number().int().optional(),
      entity_types: z.array(z.string()).optional(),
      max_depth: z.number().int().optional(),
    },
    async (args) => {
      try {
        switch (args.mode) {
          case "ancestors": {
            reqArg(args, "uid", "ancestors");
            return ok(JSON.stringify(await getAncestors(args.uid, args.depth, args.project), null, 2));
          }
          case "descendants": {
            reqArg(args, "uid", "descendants");
            return ok(JSON.stringify(await getDescendants(args.uid, args.depth, args.project), null, 2));
          }
          case "paths": {
            reqArg(args, "source", "paths"); reqArg(args, "target", "paths");
            return ok(JSON.stringify(await findPaths(args.source, args.target, args.project), null, 2));
          }
          case "find_paths": {
            // lib.js: findGraphPaths(sourceNodeId, targetNodeId, project, entityTypes, maxDepth)
            reqArg(args, "source", "find_paths"); reqArg(args, "target", "find_paths");
            return ok(JSON.stringify(await findGraphPaths(args.source, args.target, args.project, args.entity_types, args.max_depth), null, 2));
          }
          case "subgraph": {
            // lib.js: extractSubgraph(rootNodeIds, project, entityTypes, maxDepth)
            reqArg(args, "roots", "subgraph");
            return ok(JSON.stringify(await extractSubgraph(args.roots, args.project, args.entity_types, args.max_depth), null, 2));
          }
          case "traverse": {
            // lib.js: traverseGraph(rootNodeIds, project, entityTypes, maxDepth)
            reqArg(args, "roots", "traverse");
            return ok(JSON.stringify(await traverseGraph(args.roots, args.project, args.entity_types, args.max_depth), null, 2));
          }
          case "visualization": {
            return ok(JSON.stringify(await getGraphVisualization(args.project, args.entity_types), null, 2));
          }
          default: return err(new Error(`Unknown mode: ${args.mode}`));
        }
      } catch (e) { return err(e); }
    },
  );
}
