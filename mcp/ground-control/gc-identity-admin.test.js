import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  GC_IDENTITY_ADMIN_ACTIONS,
  gcIdentityAdminSchema,
  gcIdentityAdminToolHandler,
} from "./gc-identity-admin.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;
const ORIGINAL_ADMIN_TOKEN = process.env.GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN;

function makeFetchSpy({ status = 200, body = { content: [] } } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({
      url: url.toString(),
      method: opts?.method ?? "GET",
      body: opts?.body ? JSON.parse(opts.body) : null,
      authorization: opts?.headers?.Authorization,
    });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

beforeEach(() => {
  process.env.GC_BASE_URL = "https://gc.test";
  delete process.env.GROUND_CONTROL_API_TOKEN;
  process.env.GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN = "admin-token";
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
  if (ORIGINAL_API_TOKEN === undefined) delete process.env.GROUND_CONTROL_API_TOKEN;
  else process.env.GROUND_CONTROL_API_TOKEN = ORIGINAL_API_TOKEN;
  if (ORIGINAL_ADMIN_TOKEN === undefined) {
    delete process.env.GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN;
  } else {
    process.env.GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN = ORIGINAL_ADMIN_TOKEN;
  }
});

describe("gc_identity_admin contract", () => {
  it("has a closed action set and strictly rejects credential fields", () => {
    assert.equal(GC_IDENTITY_ADMIN_ACTIONS.includes("create_user"), true);
    assert.equal(GC_IDENTITY_ADMIN_ACTIONS.includes("create_credential"), false);
    assert.equal(gcIdentityAdminSchema.safeParse({
      action: "create_user",
      login_name: "alice",
      display_name: "Alice",
      user_kind: "HUMAN",
      password: "must-not-enter-transcripts",
    }).success, false);
  });

  it("creates identity users without credential or actor fields", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "user-id" } });

    await gcIdentityAdminToolHandler({
      action: "create_user",
      login_name: "alice",
      display_name: "Alice",
      user_kind: "HUMAN",
    });

    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/admin\/identity\/users$/);
    assert.deepEqual(calls[0].body, {
      loginName: "alice",
      displayName: "Alice",
      kind: "HUMAN",
    });
    assert.equal(calls[0].authorization, "Bearer admin-token");
  });

  it("keeps project routing out of a role-grant request body", async () => {
    const calls = makeFetchSpy({ status: 201, body: { id: "grant-id" } });

    await gcIdentityAdminToolHandler({
      action: "create_role_grant",
      role_id: "11111111-1111-1111-1111-111111111111",
      user_id: "22222222-2222-2222-2222-222222222222",
      project: "ground-control",
    });

    assert.match(calls[0].url, /\/api\/v1\/admin\/identity\/role-grants\?project=ground-control$/);
    assert.deepEqual(calls[0].body, {
      roleId: "11111111-1111-1111-1111-111111111111",
      userId: "22222222-2222-2222-2222-222222222222",
    });
  });

  it("rejects a grant with zero subjects before making a request", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcIdentityAdminToolHandler({
        action: "create_project_access_grant",
        project: "ground-control",
      }),
      /exactly one of user_id or group_id/,
    );
  });
});
