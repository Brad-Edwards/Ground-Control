// MCP tool usage telemetry — handler-boundary capture (ADR-059, issue #1104).
//
// Security constraints (from the preflight / ADR-059):
//   - Record ONLY the closed event shape: {tool, action, outcome, duration_ms, project, ts}.
//   - Never log args, prompts, response bodies, payloads, or bearer material.
//   - Fail-open: a telemetry write failure must never change or suppress the original result.
//   - Exactly ONE event per MCP tool call, captured after the original result is determined.

import { buildUrl, addAuthorizationHeader, RequestError, parseErrorBody } from "./lib.js";

// ---------------------------------------------------------------------------
// Closed event builder
// ---------------------------------------------------------------------------

/**
 * Build a telemetry event object containing ONLY the closed-shape fields.
 * All other data (args, response body, stack traces) is excluded.
 *
 * @param {object} p
 * @param {string}  p.tool        Registered MCP tool name
 * @param {string|null} p.action  Stable action discriminator or null
 * @param {string}  p.outcome     "ok" or a stable error code
 * @param {number}  p.durationMs  Handler latency (non-negative integer ms)
 * @param {string|null} p.project Project identifier or null
 * @returns {{ tool: string, action: string|null, outcome: string, duration_ms: number, project: string|null, ts: string }}
 */
export function buildToolEvent({ tool, action, outcome, durationMs, project }) {
  return {
    tool,
    action: action ?? null,
    outcome,
    duration_ms: durationMs,
    project: project ?? null,
    ts: new Date().toISOString(),
  };
}

// ---------------------------------------------------------------------------
// Telemetry POST — fail-open, bounded log
// ---------------------------------------------------------------------------

/**
 * POST one closed telemetry event to the backend capture endpoint.
 *
 * Fail-open: any network / auth / validation failure is caught and logged
 * with ONLY tool + outcome + failure class. The function never throws.
 *
 * @param {object} event  Object from buildToolEvent()
 * @param {Function} [fetchImpl]  Optional fetch override (for tests)
 */
export async function recordToolEvent(event, fetchImpl) {
  const doFetch = fetchImpl ?? globalThis.fetch;
  const path = "/api/v1/mcp-tool-usage/events";
  try {
    const gcBaseUrl = process.env.GC_BASE_URL?.trim();
    // If GC_BASE_URL is not set there is no backend to post to; fail silently
    // (same behaviour as buildUrl throwing, but we catch that below anyway).
    if (!gcBaseUrl) {
      return;
    }

    const url = buildUrl(path, undefined);
    const headers = {
      "Content-Type": "application/json",
      "X-Actor": "mcp-server",
    };
    addAuthorizationHeader(path, headers);

    // Map the closed event to the backend DTO's camelCase fields. lib.js
    // toCamelCase only renames its registered field allowlist, not duration_ms,
    // so the duration field is mapped explicitly here.
    const body = {
      tool: event.tool,
      action: event.action,
      outcome: event.outcome,
      durationMs: event.duration_ms,
      project: event.project,
      ts: event.ts,
    };

    const res = await doFetch(url, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const text = await res.text();
      const envelope = parseErrorBody(text);
      const code = envelope.code ?? `http_${res.status}`;
      console.error(
        `[telemetry] record failed: tool=${event.tool} outcome=${event.outcome} failure=${code}`,
      );
    }
  } catch (e) {
    // Bounded log: tool + outcome + failure class only (no payload, no tokens).
    const failureClass = e instanceof RequestError ? e.code ?? e.name : (e.code ?? e.name ?? "unknown");
    console.error(
      `[telemetry] record failed: tool=${event.tool} outcome=${event.outcome} failure=${failureClass}`,
    );
  }
}

// ---------------------------------------------------------------------------
// Handler wrapper — installToolTelemetry
// ---------------------------------------------------------------------------

let _installed = false;

/**
 * Monkey-patch server.tool and server.registerTool so every registered tool
 * callback is wrapped with telemetry capture.
 *
 * - Captures start time BEFORE the original callback runs.
 * - Awaits the original callback result (never suppresses errors; on throw,
 *   records outcome then re-throws).
 * - Computes durationMs (>= 0) around the original handler only.
 * - Reads ONLY top-level declared args.action (string) and args.project
 *   (string); null for absent / non-string. Never reads nested payloads.
 * - Classifies outcome: if result.isError === true uses
 *   result._meta?.["groundcontrol/outcomeCode"] ?? "error"; otherwise "ok".
 * - Calls recordToolEvent AFTER the result is computed (fail-open; swallowed).
 * - Returns the ORIGINAL result unchanged.
 * - Guarantees EXACTLY ONE event per call.
 *
 * Safe to call multiple times; only the first call patches (idempotent).
 *
 * @param {object} server  McpServer instance (from @modelcontextprotocol/sdk)
 * @param {Function} [fetchImpl]  Optional fetch override forwarded to recordToolEvent
 */
export function installToolTelemetry(server, fetchImpl) {
  if (_installed) return;
  _installed = true;

  const originalTool = server.tool.bind(server);
  const originalRegisterTool = server.registerTool.bind(server);

  function wrapCallback(toolName, cb) {
    return async function wrappedToolCallback(args, extra) {
      const start = Date.now();
      let result;
      let outcome = "ok";

      try {
        result = await cb(args, extra);
        if (result && result.isError === true) {
          outcome = result._meta?.["groundcontrol/outcomeCode"] ?? "error";
        }
      } catch (e) {
        // Handler threw: record the failure then re-throw.
        const elapsed = Math.max(0, Date.now() - start);
        const action =
          args && typeof args.action === "string" && args.action.length <= 200
            ? args.action
            : null;
        const project =
          args && typeof args.project === "string" && args.project.length <= 200
            ? args.project
            : null;
        const errOutcome =
          e instanceof RequestError && e.code ? e.code : (e.name ?? "error");
        const event = buildToolEvent({
          tool: toolName,
          action,
          outcome: errOutcome,
          durationMs: elapsed,
          project,
        });
        // Fire-and-forget; never await in a throw path to avoid masking the error.
        recordToolEvent(event, fetchImpl).catch(() => {});
        throw e;
      }

      const elapsed = Math.max(0, Date.now() - start);
      const action =
        args && typeof args.action === "string" && args.action.length <= 200
          ? args.action
          : null;
      const project =
        args && typeof args.project === "string" && args.project.length <= 200
          ? args.project
          : null;

      const event = buildToolEvent({ tool: toolName, action, outcome, durationMs: elapsed, project });
      // Fire-and-forget after the result is determined (fail-open).
      recordToolEvent(event, fetchImpl).catch(() => {});

      return result;
    };
  }

  // Patch server.tool(name, description, schema, cb) — the 4-arg form.
  server.tool = function patchedTool(name, description, schema, cb) {
    // server.tool has multiple overloads; detect the callback position.
    if (typeof cb === "function") {
      return originalTool(name, description, schema, wrapCallback(name, cb));
    }
    // 3-arg form: server.tool(name, schema, cb)
    if (typeof schema === "function") {
      return originalTool(name, description, wrapCallback(name, schema));
    }
    // 2-arg form: server.tool(name, cb)
    if (typeof description === "function") {
      return originalTool(name, wrapCallback(name, description));
    }
    return originalTool(name, description, schema, cb);
  };

  // Patch server.registerTool(name, config, cb).
  server.registerTool = function patchedRegisterTool(name, config, cb) {
    if (typeof cb === "function") {
      return originalRegisterTool(name, config, wrapCallback(name, cb));
    }
    return originalRegisterTool(name, config, cb);
  };
}

/**
 * Reset installation state (test helper only).
 * @internal
 */
export function _resetTelemetryInstallForTest() {
  _installed = false;
}
