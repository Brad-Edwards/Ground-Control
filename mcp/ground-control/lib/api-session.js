// Current-principal read helper (GC-Q015). Mirrors the api-history.js completeness pattern: the
// read is reachable via gc_query against the allowlisted /api/v1/session path, and the helper is
// kept for API-surface completeness. The endpoint returns display identity and presentation hints
// only — no credentials, session id, or CSRF material.

import { request } from "./api-controls-2.js";

export async function getCurrentSession() {
  return request("GET", "/api/v1/session");
}
