// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  PACK_TYPES,
  PLUGIN_TYPES,
  TRUST_OUTCOMES,
  TRUST_POLICY_FIELDS,
  TRUST_POLICY_RULE_OPERATORS,
  checkPackCompatibility,
  createProject,
  createTrustPolicy,
  deleteAdminUser,
  deletePackRegistryEntry,
  deleteTrustPolicy,
  embedProject,
  embedRequirement,
  exportAuditTimeline,
  exportDocument,
  exportRequirements,
  exportSweepReport,
  getEmbeddingStatus,
  getPackInstallRecord,
  getPackRegistryEntry,
  getPlugin,
  getTrustPolicy,
  importPackRegistryEntry,
  importReqif,
  importStrictdoc,
  installPackFromRegistry,
  listAdminUsers,
  listPackInstallRecords,
  listPackRegistryEntries,
  listPackVersions,
  listPlugins,
  listProjects,
  listTrustPolicies,
  materializeGraph,
  pick,
  registerPackRegistryEntry,
  registerPlugin,
  replaceResearchIntake,
  reqArg,
  resolvePack,
  runSweep,
  runSweepAll,
  syncGithub,
  syncGithubPrs,
  unregisterPlugin,
  updateAdminUserEnabled,
  updateAdminUserRole,
  updatePackRegistryEntry,
  updateTrustPolicy,
  upgradePackFromRegistry,
  withdrawPackRegistryEntry,
} from "../lib.js";
import { GC_IDENTITY_ADMIN_DESCRIPTION, gcIdentityAdminSchema, gcIdentityAdminToolHandler } from "../gc-identity-admin.js";
import {
  GC_INTEGRATION_MANAGER_DESCRIPTION,
  GC_INTEGRATION_MANAGER_INPUT_SCHEMA,
  runIntegrationManager,
} from "../gc-integrate.js";
import { GC_WORKFLOW_RUN_DESCRIPTION, gcWorkflowRunToolHandler, gcWorkflowRunZodShape } from "../gc-workflow-run.js";
import {
  GC_WORKFLOW_RUN_INGEST_DESCRIPTION,
  gcWorkflowRunIngestHandler,
  gcWorkflowRunIngestZodShape,
} from "../gc-workflow-run-ingest.js";
import { ok, err } from "./respond.js";

export const ADMIN_ACTIONS = [
  "import_strictdoc", "import_reqif", "sync_github", "sync_github_prs",
  "embed_requirement", "embed_project", "embedding_status",
  "materialize_graph", "create_project", "list_projects",
  "replace_research_intake",
  "run_sweep", "run_sweep_all",
  "export_audit_timeline", "export_requirements", "export_sweep_report", "export_document",
];

export const PACK_SUBSYSTEMS = ["plugin", "registry", "trust_policy", "install"];

export const PACK_ACTIONS = [
  // plugin
  "register", "unregister", "list_plugins", "get_plugin",
  // registry
  "registry_register", "import", "registry_update", "withdraw",
  "registry_delete", "resolve", "check_compatibility",
  "list_pack_registry_entries", "list_pack_versions", "get_pack_registry_entry",
  // trust_policy
  "create_trust_policy", "update_trust_policy", "delete_trust_policy",
  "list_trust_policies", "get_trust_policy",
  // install
  "install", "upgrade", "list_pack_install_records", "get_pack_install_record",
];

export const PACK_FIELDS = {
  plugin: ["name", "plugin_type", "version", "endpoint_url", "config", "metadata"],
  registry: ["pack_id", "pack_type", "version", "description", "metadata", "signature", "source_url"],
  trust_policy: ["name", "field", "operator", "value", "outcome", "priority", "metadata"],
  install: ["pack_id", "version", "scope", "config", "metadata"],
};


export function registerAdmin(server, ctx) {
  const { ADMIN_TOOLS_ENABLED } = ctx;

  if (ADMIN_TOOLS_ENABLED) {
    server.tool(
      "gc_admin",
      `Admin operations: imports, GitHub sync, embeddings, materialization, project create, sweep, exports. ` +
        `Registered only when GC_MCP_ADMIN=1 (these operations require ROLE_ADMIN at the backend per ADR-026). ` +
        `Actions: ${ADMIN_ACTIONS.join(", ")}.`,
      {
        action: z.enum(ADMIN_ACTIONS),
        project: z.string().optional(),
        file_path: z.string().optional(),
        owner: z.string().optional(),
        repo: z.string().optional(),
        requirement_id: z.string().uuid().optional(),
        force: z.boolean().optional(),
        identifier: z.string().optional(),
        name: z.string().optional(),
        description: z.string().optional(),
        document_id: z.string().uuid().optional(),
        format: z.string().optional(),
        from: z.string().optional(),
        to: z.string().optional(),
        // Project type + research intake (ADR-056, issue #999). GRC is a legacy value
        // (ADR-089 §4): readable on existing projects but not offered for new creation.
        type: z.enum(["SOFTWARE", "RESEARCH"]).optional(),
        research_intake: z.object({
          goal: z.string(),
          paperContext: z.string().optional(),
          contributionType: z.enum([
            "TAXONOMY", "REVIEW", "EMPIRICAL_STUDY", "METHODOLOGY", "POSITION", "OTHER",
          ]),
          intendedOutput: z.enum([
            "SCOPING_REVIEW", "SYSTEMATIC_REVIEW", "SYSTEMATIC_MAP", "CRITICAL_REVIEW",
            "NARRATIVE_REVIEW", "TARGETED_RELATED_WORK", "TAXONOMY_PAPER", "OTHER",
          ]),
          autonomyLevel: z.enum(["COPILOT", "AUTONOMOUS"]),
          allowedTools: z.array(z.string()),
          privacyConstraints: z.string().optional(),
          budgetTokens: z.number().int().nonnegative().optional(),
          budgetWallClockMinutes: z.number().int().nonnegative().optional(),
          budgetCostUsdMicros: z.number().int().nonnegative().optional(),
        }).optional(),
      },
      async (args) => {
        try {
          switch (args.action) {
            case "import_strictdoc": reqArg(args, "file_path", "import_strictdoc"); return ok(JSON.stringify(await importStrictdoc(args.file_path, args.project), null, 2));
            case "import_reqif": reqArg(args, "file_path", "import_reqif"); return ok(JSON.stringify(await importReqif(args.file_path, args.project), null, 2));
            case "sync_github": reqArg(args, "owner", "sync_github"); reqArg(args, "repo", "sync_github"); return ok(JSON.stringify(await syncGithub(args.owner, args.repo), null, 2));
            case "sync_github_prs": reqArg(args, "owner", "sync_github_prs"); reqArg(args, "repo", "sync_github_prs"); return ok(JSON.stringify(await syncGithubPrs(args.owner, args.repo), null, 2));
            case "embed_requirement": reqArg(args, "requirement_id", "embed_requirement"); return ok(JSON.stringify(await embedRequirement(args.requirement_id), null, 2));
            case "embed_project": return ok(JSON.stringify(await embedProject(args.project, args.force), null, 2));
            case "embedding_status": reqArg(args, "requirement_id", "embedding_status"); return ok(JSON.stringify(await getEmbeddingStatus(args.requirement_id), null, 2));
            case "materialize_graph": return ok(JSON.stringify(await materializeGraph(), null, 2));
            case "list_projects": return ok(JSON.stringify(await listProjects(), null, 2));
            case "create_project": {
              reqArg(args, "identifier", "create_project"); reqArg(args, "name", "create_project");
              // type + researchIntake are optional (ADR-056); the backend defaults
              // type to SOFTWARE and enforces "researchIntake iff type=RESEARCH".
              const body = {
                identifier: args.identifier,
                name: args.name,
                description: args.description,
              };
              if (args.type) body.type = args.type;
              if (args.research_intake) body.researchIntake = args.research_intake;
              return ok(JSON.stringify(await createProject(body), null, 2));
            }
            case "replace_research_intake": {
              reqArg(args, "identifier", "replace_research_intake");
              reqArg(args, "research_intake", "replace_research_intake");
              return ok(JSON.stringify(await replaceResearchIntake(args.identifier, args.research_intake), null, 2));
            }
            case "run_sweep": return ok(JSON.stringify(await runSweep(args.project), null, 2));
            case "run_sweep_all": return ok(JSON.stringify(await runSweepAll(), null, 2));
            case "export_audit_timeline": return ok(JSON.stringify(await exportAuditTimeline(pick(args, ["project", "from", "to", "format"])), null, 2));
            case "export_requirements": return ok(JSON.stringify(await exportRequirements(args.project, args.format), null, 2));
            case "export_sweep_report": return ok(JSON.stringify(await exportSweepReport(args.project, args.format), null, 2));
            case "export_document": reqArg(args, "document_id", "export_document"); return ok(JSON.stringify(await exportDocument(args.document_id, args.format), null, 2));
            default: return err(new Error(`Unknown action: ${args.action}`));
          }
        } catch (e) { return err(e); }
      },
    );

    // ADR-037 admin-user lifecycle. Registered alongside gc_admin so an
    // ADMIN-role bearer token can drive user management programmatically.
    // Humans manage users via the curl/session flow documented in
    // DEPLOYMENT.md — this PR does not ship a SPA user-management page.
    //
    // **`create_user` is intentionally NOT exposed via MCP.** Passing a new
    // account password as a JSON-RPC tool argument means the password lands in
    // agent transcripts, client logs, debug output, and any observability trace
    // that captures tool-call payloads. Create users via the DEPLOYMENT.md
    // curl flow where the password stays in a mode-600 file. The actions
    // surfaced here mutate state but never accept password material;
    // createAdminUser is exported from lib.js for callers that have an
    // out-of-band secret channel, not for agents.
    const USER_ADMIN_ACTIONS = [
      "list_users", "update_role", "update_enabled", "delete_user",
    ];
    server.tool(
      "gc_user_admin",
      `Admin user lifecycle (ADR-037): list / change-role / enable-disable / delete. ` +
        `Registered only when GC_MCP_ADMIN=1; backend enforces ROLE_ADMIN. ` +
        `User CREATION is intentionally not exposed here — see DEPLOYMENT.md. ` +
        `Actions: ${USER_ADMIN_ACTIONS.join(", ")}.`,
      {
        action: z.enum(USER_ADMIN_ACTIONS),
        username: z.string().optional(),
        role: z.enum(["USER", "ADMIN"]).optional(),
        enabled: z.boolean().optional(),
      },
      async (args) => {
        try {
          switch (args.action) {
            case "list_users":
              return ok(JSON.stringify(await listAdminUsers(), null, 2));
            case "update_role":
              reqArg(args, "username", "update_role");
              reqArg(args, "role", "update_role");
              return ok(JSON.stringify(await updateAdminUserRole(args.username, args.role), null, 2));
            case "update_enabled":
              reqArg(args, "username", "update_enabled");
              if (typeof args.enabled !== "boolean") {
                return err(new Error("update_enabled requires boolean 'enabled'"));
              }
              return ok(JSON.stringify(await updateAdminUserEnabled(args.username, args.enabled), null, 2));
            case "delete_user":
              reqArg(args, "username", "delete_user");
              await deleteAdminUser(args.username);
              return ok(`Deleted user '${args.username}'`);
            default:
              return err(new Error(`Unknown action: ${args.action}`));
          }
        } catch (e) {
          return err(e);
        }
      },
    );

    server.registerTool(
      "gc_identity_admin",
      {
        description: GC_IDENTITY_ADMIN_DESCRIPTION,
        inputSchema: gcIdentityAdminSchema,
      },
      async (args) => {
        try {
          return ok(JSON.stringify(await gcIdentityAdminToolHandler(args), null, 2));
        } catch (e) {
          return err(e);
        }
      },
    );
  }

  if (ADMIN_TOOLS_ENABLED) {
    server.tool(
      "gc_pack",
      `Pack ecosystem: plugins, pack registry, trust policies, install records. ` +
        `Registered only when GC_MCP_ADMIN=1 (these endpoints are ROLE_ADMIN per ADR-026 and denylisted by gc_query). ` +
        `Subsystem: ${PACK_SUBSYSTEMS.join(", ")}. Actions: ${PACK_ACTIONS.join(", ")}.`,
      {
        subsystem: z.enum(PACK_SUBSYSTEMS),
        action: z.enum(PACK_ACTIONS),
        project: z.string().optional(),
        // plugin
        name: z.string().optional(),
        plugin_type: z.enum(PLUGIN_TYPES).optional(),
        capability: z.string().optional(),
        version: z.string().optional(),
        endpoint_url: z.string().optional(),
        config: z.record(z.any()).optional(),
        metadata: z.record(z.any()).optional(),
        // registry
        pack_id: z.string().uuid().optional(),
        pack_type: z.enum(PACK_TYPES).optional(),
        file_path: z.string().optional(),
        description: z.string().optional(),
        signature: z.string().optional(),
        source_url: z.string().optional(),
        // trust_policy
        policy_id: z.string().uuid().optional(),
        field: z.enum(TRUST_POLICY_FIELDS).optional(),
        operator: z.enum(TRUST_POLICY_RULE_OPERATORS).optional(),
        value: z.string().optional(),
        outcome: z.enum(TRUST_OUTCOMES).optional(),
        priority: z.number().int().optional(),
        // install
        install_record_id: z.string().uuid().optional(),
        scope: z.string().optional(),
      },
      async (args) => {
        try {
          switch (args.subsystem) {
            case "plugin": {
              const data = pick(args, PACK_FIELDS.plugin);
              switch (args.action) {
                case "register": return ok(JSON.stringify(await registerPlugin(data, args.project), null, 2));
                case "unregister": reqArg(args, "name", "unregister"); await unregisterPlugin(args.name, args.project); return ok("Unregistered");
                case "list_plugins": return ok(JSON.stringify(await listPlugins(pick(args, ["plugin_type", "capability", "project"])), null, 2));
                case "get_plugin": reqArg(args, "name", "get_plugin"); return ok(JSON.stringify(await getPlugin(args.name), null, 2));
                default: return err(new Error(`Action '${args.action}' not valid for plugin`));
              }
            }
            case "registry": {
              const data = pick(args, PACK_FIELDS.registry);
              switch (args.action) {
                case "registry_register": return ok(JSON.stringify(await registerPackRegistryEntry(data, args.project), null, 2));
                case "import": reqArg(args, "file_path", "import"); return ok(JSON.stringify(await importPackRegistryEntry(args.file_path, data, args.project), null, 2));
                case "registry_update": reqArg(args, "pack_id", "registry_update"); reqArg(args, "version", "registry_update"); return ok(JSON.stringify(await updatePackRegistryEntry(args.pack_id, args.version, data, args.project), null, 2));
                case "withdraw": reqArg(args, "pack_id", "withdraw"); reqArg(args, "version", "withdraw"); return ok(JSON.stringify(await withdrawPackRegistryEntry(args.pack_id, args.version, args.project), null, 2));
                case "registry_delete": reqArg(args, "pack_id", "registry_delete"); reqArg(args, "version", "registry_delete"); await deletePackRegistryEntry(args.pack_id, args.version, args.project); return ok("Deleted");
                case "resolve": return ok(JSON.stringify(await resolvePack(data, args.project), null, 2));
                case "check_compatibility": return ok(JSON.stringify(await checkPackCompatibility(data, args.project), null, 2));
                case "list_pack_registry_entries": return ok(JSON.stringify(await listPackRegistryEntries(args.project, pick(args, ["pack_type"])), null, 2));
                case "list_pack_versions": reqArg(args, "pack_id", "list_pack_versions"); return ok(JSON.stringify(await listPackVersions(args.pack_id, args.project), null, 2));
                case "get_pack_registry_entry": reqArg(args, "pack_id", "get_pack_registry_entry"); reqArg(args, "version", "get_pack_registry_entry"); return ok(JSON.stringify(await getPackRegistryEntry(args.pack_id, args.version, args.project), null, 2));
                default: return err(new Error(`Action '${args.action}' not valid for registry`));
              }
            }
            case "trust_policy": {
              const data = pick(args, PACK_FIELDS.trust_policy);
              switch (args.action) {
                case "create_trust_policy": return ok(JSON.stringify(await createTrustPolicy(data, args.project), null, 2));
                case "update_trust_policy": reqArg(args, "policy_id", "update_trust_policy"); return ok(JSON.stringify(await updateTrustPolicy(args.policy_id, data), null, 2));
                case "delete_trust_policy": reqArg(args, "policy_id", "delete_trust_policy"); await deleteTrustPolicy(args.policy_id); return ok("Deleted");
                case "list_trust_policies": return ok(JSON.stringify(await listTrustPolicies(args.project), null, 2));
                case "get_trust_policy": reqArg(args, "policy_id", "get_trust_policy"); return ok(JSON.stringify(await getTrustPolicy(args.policy_id), null, 2));
                default: return err(new Error(`Action '${args.action}' not valid for trust_policy`));
              }
            }
            case "install": {
              const data = pick(args, PACK_FIELDS.install);
              switch (args.action) {
                case "install": return ok(JSON.stringify(await installPackFromRegistry(data, args.project), null, 2));
                case "upgrade": return ok(JSON.stringify(await upgradePackFromRegistry(data, args.project), null, 2));
                case "list_pack_install_records": return ok(JSON.stringify(await listPackInstallRecords(args.project, pick(args, ["pack_id"])), null, 2));
                case "get_pack_install_record": reqArg(args, "install_record_id", "get_pack_install_record"); return ok(JSON.stringify(await getPackInstallRecord(args.install_record_id), null, 2));
                default: return err(new Error(`Action '${args.action}' not valid for install`));
              }
            }
            default: return err(new Error(`Unknown subsystem: ${args.subsystem}`));
          }
        } catch (e) { return err(e); }
      },
    );
  }

  server.tool(
    "gc_integration_manager",
    GC_INTEGRATION_MANAGER_DESCRIPTION,
    {
      action: z.enum(["plan", "prepare", "status", "release"]),
      repo_path: z.string().min(1),
      mode: z.enum(["prepare", "enqueue", "merge"]).optional(),
    },
    async (args) => {
      try {
        return ok(JSON.stringify(await runIntegrationManager(args), null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_workflow_run",
    GC_WORKFLOW_RUN_DESCRIPTION,
    gcWorkflowRunZodShape,
    async (args) => {
      // The record action is an idempotent upsert keyed by (project, repo, issue, branch). The
      // admin-only cross_project_aggregate action is gated behind GC_MCP_ADMIN so a default MCP
      // session cannot reach cross-project operational telemetry (issue #859 security review).
      try { return ok(JSON.stringify(await gcWorkflowRunToolHandler(args, { adminEnabled: ADMIN_TOOLS_ENABLED }), null, 2)); }
      catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_workflow_run_ingest",
    GC_WORKFLOW_RUN_INGEST_DESCRIPTION,
    gcWorkflowRunIngestZodShape,
    async (args) => {
      try { return ok(JSON.stringify(await gcWorkflowRunIngestHandler(args), null, 2)); }
      catch (e) { return err(e); }
    },
  );
}
