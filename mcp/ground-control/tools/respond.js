// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Bodies are unchanged.
//
// The MCP tool result envelopes. These are pure, so unlike
// ADMIN_TOOLS_ENABLED they are safe to evaluate at import time.

export function ok(text) {
  return { content: [{ type: "text", text }] };
}
export function err(e) {
  let text = `Error: ${e.message}`;
  if (e && e.name === "RequestError") {
    if (e.code) text += ` (${e.code})`;
    if (e.detail && typeof e.detail === "object" && Object.keys(e.detail).length > 0) {
      text += `\nDetail: ${JSON.stringify(e.detail, null, 2)}`;
    }
  }
  const outcomeCode = (e && e.name === "RequestError" && e.code) ? e.code : "error";
  return {
    content: [{ type: "text", text }],
    isError: true,
    _meta: { "groundcontrol/outcomeCode": outcomeCode },
  };
}
