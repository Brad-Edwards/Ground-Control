// gc_query: read-only REST escape hatch for ad-hoc agent queries (ADR-035).
//
// The MCP-side surface is intentionally narrow:
//   - GET only (no method parameter exists)
//   - /api/v1/** paths only, allowlisted prefixes only, denylist takes precedence
//   - flat params object, primitive values only
//   - 30s timeout via AbortController, kept alive through body consumption
//   - 1 MiB response-body cap, enforced by streaming the body and stopping the
//     reader once the cap is reached (does NOT buffer unbounded before the cap)
//   - no caller-supplied headers (the MCP schema does not expose any)
//   - reuses lib.js's buildUrl, addAuthorizationHeader, RequestError, and
//     parseErrorBody so error semantics match the rest of the adapter.
//
// `gc_query` is the read fallback for every REST endpoint not covered by a
// named consolidated tool. After the consolidation in ADR-035, the named
// tools cover writes + non-trivial compute; pure GETs (history, timeline,
// list-by-X, exports) flow through `gc_query`.

import { z } from "zod";
import {
  buildUrl,
  parseErrorBody,
  RequestError,
  addAuthorizationHeader,
} from "./lib.js";

// ---------------------------------------------------------------------------
// gc_query: path / params validation, body truncation, cost cap
// ---------------------------------------------------------------------------

/**
 * Read-oriented `/api/v1/**` prefixes that gc_query is allowed to GET. This
 * is intentionally narrower than the backend's authenticated read surface:
 * default-deny for ad-hoc agent queries. Adding a new prefix is a deliberate
 * decision; the README documents the maintenance step.
 */
export const GC_QUERY_PATH_ALLOWLIST = Object.freeze([
  "/api/v1/adrs",
  "/api/v1/analysis",
  "/api/v1/assets",
  "/api/v1/audit",
  "/api/v1/audits",
  "/api/v1/baselines",
  "/api/v1/control-effectiveness-assessments",
  "/api/v1/control-tests",
  "/api/v1/controls",
  "/api/v1/dashboard",
  "/api/v1/documents",
  "/api/v1/evidence-artifacts",
  "/api/v1/findings",
  "/api/v1/graph",
  "/api/v1/methodology-profiles",
  "/api/v1/observations",
  "/api/v1/projects",
  "/api/v1/quality-gates",
  "/api/v1/relations",
  "/api/v1/requirements",
  "/api/v1/risk-assessment-results",
  "/api/v1/risk-register-records",
  "/api/v1/risk-scenarios",
  "/api/v1/sections",
  "/api/v1/test-cases",
  "/api/v1/test-plans",
  "/api/v1/test-runs",
  "/api/v1/test-suites",
  "/api/v1/threat-models",
  "/api/v1/timeline",
  "/api/v1/traceability",
  "/api/v1/treatment-plans",
  "/api/v1/verification-results",
  // GC-T005 / T006 / T007 risk-governance lifecycle reads
  "/api/v1/risk-appetite-profiles",
  "/api/v1/risk-assessment-campaigns",
  "/api/v1/key-risk-indicators",
]);

/**
 * Prefixes that gc_query refuses even though they live under /api/v1/. The
 * backend's path matrix (ADR-026) also rejects them for non-ROLE_ADMIN
 * principals; defense in depth is the point. The denylist takes precedence
 * over the allowlist when they overlap (e.g., /api/v1/analysis is allowed
 * but /api/v1/analysis/sweep is denied).
 */
export const GC_QUERY_PATH_DENYLIST = Object.freeze([
  "/api/v1/admin",
  "/api/v1/analysis/sweep",
  "/api/v1/embeddings",
  "/api/v1/pack-registry",
]);

/** 1 MiB body cap for gc_query responses. */
export const GC_QUERY_BODY_BYTE_CAP = 1024 * 1024;

/** 30s wall-clock timeout for gc_query requests. */
export const GC_QUERY_TIMEOUT_MS = 30_000;

class GcQueryValidationError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.name = "GcQueryValidationError";
    this.code = code;
  }
}

/**
 * Throw GcQueryValidationError unless `path` is a valid gc_query target.
 */
export function validateGcQueryPath(path) {
  if (typeof path !== "string" || path.length === 0) {
    throw new GcQueryValidationError("invalid_query_path", "path must be a non-empty string");
  }
  if (path.startsWith("//") || /^[a-z][a-z0-9+.-]*:/i.test(path)) {
    throw new GcQueryValidationError(
      "invalid_query_path",
      `absolute or protocol-relative URLs are not allowed: ${path}`,
    );
  }
  if (path.includes("..")) {
    throw new GcQueryValidationError("invalid_query_path", `path must not contain '..': ${path}`);
  }
  if (path.includes("?") || path.includes("#")) {
    throw new GcQueryValidationError(
      "invalid_query_path",
      `path must not contain '?' or '#'; use the params field instead: ${path}`,
    );
  }
  for (const denied of GC_QUERY_PATH_DENYLIST) {
    if (path === denied || path.startsWith(`${denied}/`)) {
      throw new GcQueryValidationError(
        "invalid_query_path",
        `path is in the gc_query denylist (${denied}): ${path}`,
      );
    }
  }
  const allowed = GC_QUERY_PATH_ALLOWLIST.some(
    (prefix) => path === prefix || path.startsWith(`${prefix}/`),
  );
  if (!allowed) {
    throw new GcQueryValidationError(
      "invalid_query_path",
      `path is not in the gc_query allowlist: ${path}`,
    );
  }
  // Strict character whitelist — no percent-encoding (`%2e%2e` would otherwise
  // canonicalize past the literal-`..` check at fetch time and bypass the
  // denylist), no backslash, no `@/;` etc.
  if (!/^[A-Za-z0-9/_.-]+$/.test(path)) {
    throw new GcQueryValidationError(
      "invalid_query_path",
      `path contains characters outside [A-Za-z0-9/_.-]; use 'params' for any query value: ${path}`,
    );
  }
}

/**
 * Throw GcQueryValidationError unless `params` is a valid gc_query params
 * object: undefined, null, or a flat object whose values are
 * string|number|boolean|null.
 */
export function validateGcQueryParams(params) {
  if (params === undefined || params === null) return;
  if (typeof params !== "object" || Array.isArray(params)) {
    throw new GcQueryValidationError("invalid_query_params", "params must be a flat object");
  }
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    const t = typeof v;
    if (t === "string" || t === "number" || t === "boolean") continue;
    throw new GcQueryValidationError(
      "invalid_query_params",
      `params.${k}: only string|number|boolean|null are allowed (got ${Array.isArray(v) ? "array" : t})`,
    );
  }
}

/**
 * Cap a response body string at GC_QUERY_BODY_BYTE_CAP bytes. Used as the
 * fallback path when the response doesn't expose a stream (test stubs).
 */
export function truncateBody(body) {
  const originalByteLength = Buffer.byteLength(body, "utf8");
  if (originalByteLength <= GC_QUERY_BODY_BYTE_CAP) {
    return { body, truncated: false, original_byte_length: originalByteLength };
  }
  const buf = Buffer.from(body, "utf8").subarray(0, GC_QUERY_BODY_BYTE_CAP);
  const head = buf.toString("utf8");
  const marker = `\n\n... [gc_query response truncated at ${GC_QUERY_BODY_BYTE_CAP} bytes; original was ${originalByteLength} bytes]`;
  return {
    body: `${head}${marker}`,
    truncated: true,
    original_byte_length: originalByteLength,
  };
}

/**
 * Stream-read a Response.body up to byteCap bytes. Cancels the underlying
 * stream once the cap is reached, so the request does not buffer unbounded
 * data into memory.
 */
async function readBoundedBody(body, byteCap) {
  if (!body || typeof body.getReader !== "function") {
    return { buffer: null, hitCap: false };
  }
  const reader = body.getReader();
  const chunks = [];
  let received = 0;
  let hitCap = false;
  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      received += value.byteLength;
      chunks.push(value);
      if (received >= byteCap) {
        hitCap = true;
        break;
      }
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      // best-effort
    }
  }
  const buffer = Buffer.concat(chunks.map((c) => Buffer.from(c)));
  return { buffer, hitCap };
}

/**
 * Issue a validated, allowlisted GET against /api/v1/** and return the raw
 * response body, capped (with a clear marker) if it exceeded
 * GC_QUERY_BODY_BYTE_CAP. Body consumption shares the request's
 * AbortController so the 30s timeout covers the entire request lifetime, not
 * just header arrival.
 *
 * @returns {Promise<{status: number, body: string, truncated: boolean, original_byte_length: number}>}
 */
export async function executeGcQuery(path, params, opts = {}) {
  const fetchImpl = opts.fetchImpl ?? globalThis.fetch;
  const timeoutMs = opts.timeoutMs ?? GC_QUERY_TIMEOUT_MS;

  try {
    validateGcQueryPath(path);
    validateGcQueryParams(params);
  } catch (e) {
    if (e && e.name === "GcQueryValidationError") {
      throw new RequestError({ status: 0, code: e.code, message: e.message, detail: null });
    }
    throw e;
  }

  const url = buildUrl(path, params ?? undefined);
  const headers = { "X-Actor": "mcp-server", Accept: "application/json" };
  addAuthorizationHeader(path, headers);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    let res;
    try {
      res = await fetchImpl(url, { method: "GET", headers, signal: controller.signal });
    } catch (e) {
      if (e && (e.name === "AbortError" || e.code === "ABORT_ERR")) {
        throw new RequestError({
          status: 0,
          code: "gc_query_timeout",
          message: `gc_query timed out after ${timeoutMs}ms: ${path}`,
          detail: null,
        });
      }
      throw e;
    }

    if (res.status === 204) {
      return { status: 204, body: "", truncated: false, original_byte_length: 0 };
    }

    let bodyText;
    let streamHitCap = false;
    let streamedByteLength = 0;
    try {
      if (res.body && typeof res.body.getReader === "function") {
        const { buffer, hitCap } = await readBoundedBody(res.body, GC_QUERY_BODY_BYTE_CAP);
        streamHitCap = hitCap;
        streamedByteLength = buffer.byteLength;
        bodyText = buffer.toString("utf8");
      } else {
        bodyText = await res.text();
      }
    } catch (e) {
      if (e && (e.name === "AbortError" || e.code === "ABORT_ERR")) {
        throw new RequestError({
          status: 0,
          code: "gc_query_timeout",
          message: `gc_query timed out after ${timeoutMs}ms while reading body: ${path}`,
          detail: null,
        });
      }
      throw e;
    }

    if (!res.ok) {
      const envelope = parseErrorBody(bodyText);
      throw new RequestError({
        status: res.status,
        code: envelope.code,
        message: envelope.message,
        detail: envelope.detail,
      });
    }

    if (streamHitCap) {
      const marker = `\n\n... [gc_query response truncated at ~${GC_QUERY_BODY_BYTE_CAP} bytes (stream stopped to bound cost); original size unknown]`;
      const buf = Buffer.from(bodyText, "utf8").subarray(0, GC_QUERY_BODY_BYTE_CAP);
      return {
        status: res.status,
        body: `${buf.toString("utf8")}${marker}`,
        truncated: true,
        original_byte_length: streamedByteLength,
      };
    }

    const trunc = truncateBody(bodyText);
    return {
      status: res.status,
      body: trunc.body,
      truncated: trunc.truncated,
      original_byte_length: trunc.original_byte_length,
    };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Recognizes a runtime `AbortSignal` value. The handler must only strip a
 * `signal` key when the value really is SDK runtime plumbing; a caller-
 * supplied plain object under the same name must fall through to the
 * public-argument allowlist so the user-facing contract stays exactly
 * `path` + optional `params`. Uses `instanceof` for the same-realm case
 * and a duck-type fallback for cross-realm signals (workers, isolated
 * module realms) so a legitimate AbortSignal originating elsewhere still
 * matches.
 */
function isAbortSignalLike(v) {
  if (typeof globalThis.AbortSignal === "function" && v instanceof globalThis.AbortSignal) {
    return true;
  }
  return (
    v !== null &&
    typeof v === "object" &&
    typeof v.aborted === "boolean" &&
    typeof v.addEventListener === "function" &&
    typeof v.removeEventListener === "function"
  );
}

/**
 * Map of key → predicate that recognizes the runtime/SDK-injected value
 * shape for that key. The handler strips a key only when both the name AND
 * the value shape match; this preserves the handler's defense-in-depth
 * contract for arbitrary unknown user inputs.
 */
const SDK_INJECTED_KEY_PREDICATES = Object.freeze(
  new Map([["signal", isAbortSignalLike]]),
);

/**
 * Runtime keys that some MCP clients/transports pipe into the tool-handler
 * `args` object even though they are not user-supplied arguments. `signal`
 * (an `AbortSignal` used downstream for `AbortController` plumbing) is the
 * documented case (issue #874). Stripping happens at the adapter boundary
 * BEFORE the public-argument allowlist so the public contract stays exactly
 * `path` + optional `params` while runtime plumbing is consumed harmlessly.
 *
 * ADR-035 "Public argument boundary"; preflight note
 * `architecture/notes/mcp-tool-catalog-surface-preflight.md` "MCP runtime
 * plumbing". Growing this set is a deliberate change — keep it narrow.
 */
export const GC_QUERY_SDK_INJECTED_KEYS = Object.freeze(
  new Set(SDK_INJECTED_KEY_PREDICATES.keys()),
);

/**
 * Return a shallow copy of `args` with the known SDK-injected runtime keys
 * removed when their value matches the documented runtime shape. Does not
 * mutate the input. Does NOT strip other unknown keys (e.g. `headers`,
 * `method`): those are caller-facing and must still fail loudly via
 * `gcQueryToolHandler`'s allowlist. A `signal` key whose value is NOT an
 * AbortSignal also survives the strip and is rejected by the same allowlist
 * — the per-key value predicate is what keeps the public contract intact.
 */
export function stripSdkInjectedKeys(args) {
  if (args === undefined || args === null) return {};
  const out = {};
  for (const [k, v] of Object.entries(args)) {
    const predicate = SDK_INJECTED_KEY_PREDICATES.get(k);
    if (predicate && predicate(v)) continue;
    out[k] = v;
  }
  return out;
}

/**
 * MCP tool handler entrypoint for `gc_query`. Defense-in-depth on top of the
 * strict Zod schema below: explicitly rejects any args object key beyond
 * `path` and `params` so a future SDK relaxation can't silently let unknown
 * keys through.
 *
 * Some clients/transports (issue #874) inject runtime control fields like
 * `signal` (an `AbortSignal`) into `args` outside the Zod-validated payload.
 * Those keys are stripped via {@link stripSdkInjectedKeys} BEFORE the public
 * allowlist runs, so the public contract stays `path` + optional `params`
 * and the inbound SDK signal does not leak into URL construction, headers,
 * or the outbound `AbortController.signal` (which `executeGcQuery` builds
 * for its own 30s timeout — the two must not be conflated).
 */
export async function gcQueryToolHandler(args, opts = {}) {
  const userArgs = stripSdkInjectedKeys(args);
  const allowedKeys = new Set(["path", "params"]);
  for (const k of Object.keys(userArgs)) {
    if (!allowedKeys.has(k)) {
      throw new RequestError({
        status: 0,
        code: "invalid_query_args",
        message: `gc_query: unknown argument '${k}'. Only 'path' and 'params' are accepted.`,
        detail: null,
      });
    }
  }
  return executeGcQuery(userArgs.path, userArgs.params, opts);
}

/**
 * Strict Zod schema for `gc_query` MCP arguments. Registering a strict
 * ZodObject (rather than a raw shape) means the MCP SDK rejects unknown keys
 * at the protocol layer before the handler runs.
 */
export const gcQuerySchema = z
  .object({
    path: z
      .string()
      .describe(
        "Relative API path. Must start with one of the allowlisted /api/v1/* prefixes; absolute URLs, '..' segments, '?', and '#' are rejected.",
      ),
    params: z
      .record(z.union([z.string(), z.number(), z.boolean(), z.null()]))
      .optional()
      .describe(
        "Optional flat object of query parameters. Values must be string|number|boolean|null. undefined/null values are dropped.",
      ),
  })
  .strict();
