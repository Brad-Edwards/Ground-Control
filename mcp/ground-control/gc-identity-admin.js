import { z } from "zod";
import {
  createIdentityRecord,
  getIdentityRecord,
  listIdentityPermissions,
  listIdentityRecords,
  pick,
  reqArg,
  revokeIdentityRecord,
  updateIdentityRecord,
} from "./lib.js";

export const IDENTITY_USER_KINDS = ["HUMAN", "SERVICE"];
export const IDENTITY_USER_STATES = ["ACTIVE", "SUSPENDED", "DISABLED"];
export const IDENTITY_GROUP_STATES = ["ACTIVE", "INACTIVE"];
export const IDENTITY_ROLE_STATES = ["ACTIVE", "INACTIVE"];
export const IDENTITY_PERMISSIONS = [
  "API_ACCESS",
  "IDENTITY_ADMIN",
  "EMBEDDINGS_ADMIN",
  "ANALYSIS_SWEEP",
  "PACK_REGISTRY_ADMIN",
  "MCP_USAGE_READ",
  "WORKFLOW_RUN_CROSS_PROJECT_READ",
  "RESEARCH_OPERATION_AUTHORIZE",
  "PROJECT_READ",
  "PROJECT_WRITE",
  "PROJECT_ACCESS_ADMIN",
];

export const GC_IDENTITY_ADMIN_ACTIONS = [
  "list_permissions",
  "list_users", "get_user", "create_user", "update_user",
  "list_groups", "get_group", "create_group", "update_group",
  "list_memberships", "add_membership", "revoke_membership",
  "list_roles", "get_role", "create_role", "update_role",
  "list_role_permissions", "assign_role_permission", "revoke_role_permission",
  "list_role_grants", "create_role_grant", "revoke_role_grant",
  "list_project_access_grants", "create_project_access_grant", "revoke_project_access_grant",
];

export const IDENTITY_CREATE_USER_FIELDS = ["login_name", "display_name", "user_kind"];
export const IDENTITY_UPDATE_USER_FIELDS = ["display_name", "user_state"];
export const IDENTITY_CREATE_GROUP_FIELDS = ["name", "display_name"];
export const IDENTITY_UPDATE_GROUP_FIELDS = ["display_name", "group_state"];
export const IDENTITY_CREATE_MEMBERSHIP_FIELDS = [
  "user_id", "group_id", "effective_from", "effective_until",
];
export const IDENTITY_CREATE_ROLE_FIELDS = ["key", "display_name", "description"];
export const IDENTITY_UPDATE_ROLE_FIELDS = ["display_name", "description", "role_state"];
export const IDENTITY_ASSIGN_PERMISSION_FIELDS = ["role_id", "permission"];
export const IDENTITY_CREATE_ROLE_GRANT_FIELDS = [
  "role_id", "user_id", "group_id", "effective_from", "effective_until",
];
export const IDENTITY_CREATE_PROJECT_ACCESS_GRANT_FIELDS = [
  "user_id", "group_id", "effective_from", "effective_until",
];

export const gcIdentityAdminSchema = z.object({
  action: z.enum(GC_IDENTITY_ADMIN_ACTIONS),
  id: z.string().uuid().optional(),
  page: z.number().int().nonnegative().optional(),
  size: z.number().int().min(1).max(200).optional(),
  login_name: z.string().optional(),
  display_name: z.string().optional(),
  user_kind: z.enum(IDENTITY_USER_KINDS).optional(),
  user_state: z.enum(IDENTITY_USER_STATES).optional(),
  name: z.string().optional(),
  group_state: z.enum(IDENTITY_GROUP_STATES).optional(),
  user_id: z.string().uuid().optional(),
  group_id: z.string().uuid().optional(),
  role_id: z.string().uuid().optional(),
  key: z.string().optional(),
  description: z.string().optional(),
  role_state: z.enum(IDENTITY_ROLE_STATES).optional(),
  permission: z.enum(IDENTITY_PERMISSIONS).optional(),
  project: z.string().optional(),
  effective_from: z.string().datetime().optional(),
  effective_until: z.string().datetime().optional(),
}).strict();

export const GC_IDENTITY_ADMIN_DESCRIPTION =
  "Non-secret identity and RBAC administration (ADR-085). " +
  "Registered only when GC_MCP_ADMIN=1; backend authorization still applies. " +
  "No password, token, credential, or caller-supplied actor fields are accepted.";

const PAGE_FIELDS = ["page", "size"];

function requireId(args, action) {
  reqArg(args, "id", action);
  return args.id;
}

function requireExactlyOneSubject(args, action) {
  if ((args.user_id === undefined) === (args.group_id === undefined)) {
    throw new Error(`${action} requires exactly one of user_id or group_id`);
  }
}

function withRenamedEnum(args, fields, from, to) {
  const body = pick(args, fields);
  if (body[from] !== undefined) {
    body[to] = body[from];
    delete body[from];
  }
  return body;
}

export async function gcIdentityAdminToolHandler(args) {
  const action = args.action;
  switch (action) {
    case "list_permissions":
      return listIdentityPermissions();
    case "list_users":
      return listIdentityRecords("users", pick(args, PAGE_FIELDS));
    case "get_user":
      return getIdentityRecord("users", requireId(args, action));
    case "create_user":
      reqArg(args, "login_name", action);
      reqArg(args, "display_name", action);
      reqArg(args, "user_kind", action);
      return createIdentityRecord(
        "users",
        withRenamedEnum(args, IDENTITY_CREATE_USER_FIELDS, "user_kind", "kind"),
      );
    case "update_user":
      return updateIdentityRecord(
        "users",
        requireId(args, action),
        withRenamedEnum(args, IDENTITY_UPDATE_USER_FIELDS, "user_state", "state"),
      );
    case "list_groups":
      return listIdentityRecords("groups", pick(args, PAGE_FIELDS));
    case "get_group":
      return getIdentityRecord("groups", requireId(args, action));
    case "create_group":
      reqArg(args, "name", action);
      reqArg(args, "display_name", action);
      return createIdentityRecord("groups", pick(args, IDENTITY_CREATE_GROUP_FIELDS));
    case "update_group":
      return updateIdentityRecord(
        "groups",
        requireId(args, action),
        withRenamedEnum(args, IDENTITY_UPDATE_GROUP_FIELDS, "group_state", "state"),
      );
    case "list_memberships":
      return listIdentityRecords("memberships", pick(args, PAGE_FIELDS));
    case "add_membership":
      reqArg(args, "user_id", action);
      reqArg(args, "group_id", action);
      return createIdentityRecord("memberships", pick(args, IDENTITY_CREATE_MEMBERSHIP_FIELDS));
    case "revoke_membership":
      return revokeIdentityRecord("memberships", requireId(args, action));
    case "list_roles":
      return listIdentityRecords("roles", pick(args, PAGE_FIELDS));
    case "get_role":
      return getIdentityRecord("roles", requireId(args, action));
    case "create_role":
      reqArg(args, "key", action);
      reqArg(args, "display_name", action);
      return createIdentityRecord("roles", pick(args, IDENTITY_CREATE_ROLE_FIELDS));
    case "update_role":
      return updateIdentityRecord(
        "roles",
        requireId(args, action),
        withRenamedEnum(args, IDENTITY_UPDATE_ROLE_FIELDS, "role_state", "state"),
      );
    case "list_role_permissions":
      return listIdentityRecords("role-permissions", pick(args, PAGE_FIELDS));
    case "assign_role_permission":
      reqArg(args, "role_id", action);
      reqArg(args, "permission", action);
      return createIdentityRecord(
        "role-permissions",
        pick(args, IDENTITY_ASSIGN_PERMISSION_FIELDS),
      );
    case "revoke_role_permission":
      return revokeIdentityRecord("role-permissions", requireId(args, action));
    case "list_role_grants":
      return listIdentityRecords("role-grants", pick(args, PAGE_FIELDS));
    case "create_role_grant":
      reqArg(args, "role_id", action);
      requireExactlyOneSubject(args, action);
      return createIdentityRecord(
        "role-grants",
        pick(args, IDENTITY_CREATE_ROLE_GRANT_FIELDS),
        { project: args.project },
      );
    case "revoke_role_grant":
      return revokeIdentityRecord("role-grants", requireId(args, action));
    case "list_project_access_grants":
      return listIdentityRecords("project-access-grants", pick(args, PAGE_FIELDS));
    case "create_project_access_grant":
      reqArg(args, "project", action);
      requireExactlyOneSubject(args, action);
      return createIdentityRecord(
        "project-access-grants",
        pick(args, IDENTITY_CREATE_PROJECT_ACCESS_GRANT_FIELDS),
        { project: args.project },
      );
    case "revoke_project_access_grant":
      return revokeIdentityRecord("project-access-grants", requireId(args, action));
    default:
      throw new Error(`Unknown action: ${action}`);
  }
}
