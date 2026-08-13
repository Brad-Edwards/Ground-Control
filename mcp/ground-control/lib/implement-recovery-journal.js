// Versioned write-ahead recovery journal for the mechanical publish (issue #1495).
//
// The async publish holds a staged merge across multi-minute gates. If the MCP
// process is lost — timeout, cancellation, crash — the checkout can be left with
// a staged merge and no in-memory job to describe it. This journal is the small,
// closed, operational record a later authorized attempt reads to reconcile that
// state. It lives in the authorized per-worktree Git metadata directory, never
// the working tree, and it is NOT a workflow record: only the trusted
// issue-thread synchronization attestation authorizes PR creation (ADR-029).
//
// The shape is deliberately closed and carries no command, output, environment,
// credential, origin URL, diff, file content, idempotency key, or error prose —
// only identities, object IDs, a closed phase, a closed classification, and
// timestamps. It is validated on every read; a corrupt or unknown-schema journal
// fails closed and is never silently deleted.

import { closeSync, constants as fsConstants, fstatSync, lstatSync, openSync, readFileSync, realpathSync, renameSync, unlinkSync, writeSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { isAbsolute, join } from "node:path";
import { GIT_OBJECT_ID_RE } from "./codex-workflow.js";

export const IMPLEMENT_PUBLISH_JOURNAL_SCHEMA = "gc.implement.publish-journal/v1";
export const IMPLEMENT_PUBLISH_JOURNAL_BASENAME = "gc-implement-publish-journal.json";

// The closed publish lifecycle phases the journal may record, in order.
export const IMPLEMENT_PUBLISH_JOURNAL_PHASES = Object.freeze([
  "initializing",
  "feature_committed",
  "feature_pushed",
  "base_fetched",
  "merge_staged",
  "gates_passed",
  "merge_committed",
  "merge_pushed",
  "attested",
  "reconciling",
]);

// The closed terminal/recovery classifications.
export const IMPLEMENT_PUBLISH_JOURNAL_CLASSIFICATIONS = Object.freeze([
  "in_progress",
  "completed",
  "timed_out",
  "cancelled",
  "agent_required",
  "failed",
]);

const RECORD_ID_RE = /^[0-9a-f]{32}$/;
// Nullable object-ID fields become known as the publish advances.
const OID_OR_NULL_FIELDS = ["pre_publish_head", "published_pre_sync_head", "fetched_base_sha", "expected_merge_head"];
const REQUIRED_STRING_FIELDS = ["branch", "base_branch"];

function isOidOrNull(value) {
  return value === null || (typeof value === "string" && GIT_OBJECT_ID_RE.test(value));
}

// Resolve the journal path inside the authorized Git metadata directory. The
// basename is a fixed constant, so there is no path traversal from caller input;
// the directory is realpath'd so a symlinked git-dir cannot redirect the write
// outside the authorized metadata tree.
export function implementPublishJournalPath(gitDir) {
  if (typeof gitDir !== "string" || !isAbsolute(gitDir)) {
    throw new Error("implementPublishJournalPath: gitDir must be an absolute path");
  }
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- absolute path validated above; realpath below
  const canonical = realpathSync(gitDir);
  return join(canonical, IMPLEMENT_PUBLISH_JOURNAL_BASENAME);
}

const IMPLEMENT_PUBLISH_JOURNAL_KEYS = new Set([
  "schema", "record_id", "issue_number", "phase", "classification",
  "created_at", "updated_at", ...REQUIRED_STRING_FIELDS, ...OID_OR_NULL_FIELDS,
]);

// The per-field shape check, separate from the outer structure/schema gate so the
// validator stays under the cognitive-complexity limit. Returns null when every
// field is in shape, or "journal_shape_invalid" on the first malformed one.
function journalFieldShapeError(parsed) {
  if (parsed.record_id !== null && !(typeof parsed.record_id === "string" && RECORD_ID_RE.test(parsed.record_id))) {
    return "journal_shape_invalid";
  }
  if (!Number.isInteger(parsed.issue_number) || parsed.issue_number <= 0) {
    return "journal_shape_invalid";
  }
  for (const field of REQUIRED_STRING_FIELDS) {
    if (typeof parsed[field] !== "string" || parsed[field].length === 0 || parsed[field].length > 200) {
      return "journal_shape_invalid";
    }
  }
  for (const field of OID_OR_NULL_FIELDS) {
    if (!isOidOrNull(parsed[field])) return "journal_shape_invalid";
  }
  if (!IMPLEMENT_PUBLISH_JOURNAL_PHASES.includes(parsed.phase)) return "journal_shape_invalid";
  if (!IMPLEMENT_PUBLISH_JOURNAL_CLASSIFICATIONS.includes(parsed.classification)) return "journal_shape_invalid";
  if (typeof parsed.created_at !== "string" || typeof parsed.updated_at !== "string") {
    return "journal_shape_invalid";
  }
  return null;
}

// Validate a parsed journal object against the closed v1 shape. Returns
// { ok: true, record } or { ok: false, error }. Unknown keys, a wrong schema,
// or a malformed field all fail closed.
export function validateImplementPublishJournalRecord(parsed) {
  if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { ok: false, error: "journal_shape_invalid" };
  }
  if (parsed.schema !== IMPLEMENT_PUBLISH_JOURNAL_SCHEMA) {
    return { ok: false, error: "journal_schema_unknown" };
  }
  for (const key of Object.keys(parsed)) {
    if (!IMPLEMENT_PUBLISH_JOURNAL_KEYS.has(key)) return { ok: false, error: "journal_unknown_field" };
  }
  const shapeError = journalFieldShapeError(parsed);
  if (shapeError) return { ok: false, error: shapeError };
  return { ok: true, record: parsed };
}

// Write (create or replace) the journal atomically with 0600 permissions. `fields`
// carries the identities/object IDs/phase/classification; timestamps are stamped
// here. The write is validated before it touches disk, so a caller cannot persist
// an out-of-shape journal. `now` is injectable for deterministic tests.
export function writeImplementPublishJournal(gitDir, fields, { now = () => new Date().toISOString() } = {}) {
  const path = implementPublishJournalPath(gitDir);
  const existing = readImplementPublishJournal(gitDir);
  // Merge over any prior in-shape record so advancing the phase preserves the
  // identities recorded by earlier mutating steps (e.g. the merge record ID and
  // fetched base SHA) instead of resetting them to null.
  const prior = existing.ok && existing.present ? existing.record : null;
  const timestamp = now();
  const record = {
    schema: IMPLEMENT_PUBLISH_JOURNAL_SCHEMA,
    record_id: fields.record_id ?? prior?.record_id ?? null,
    issue_number: fields.issue_number ?? prior?.issue_number,
    branch: fields.branch ?? prior?.branch,
    base_branch: fields.base_branch ?? prior?.base_branch,
    pre_publish_head: fields.pre_publish_head ?? prior?.pre_publish_head ?? null,
    published_pre_sync_head: fields.published_pre_sync_head ?? prior?.published_pre_sync_head ?? null,
    fetched_base_sha: fields.fetched_base_sha ?? prior?.fetched_base_sha ?? null,
    expected_merge_head: fields.expected_merge_head ?? prior?.expected_merge_head ?? null,
    phase: fields.phase,
    classification: fields.classification ?? prior?.classification ?? "in_progress",
    created_at: prior?.created_at ?? timestamp,
    updated_at: timestamp,
  };
  const validation = validateImplementPublishJournalRecord(record);
  if (!validation.ok) {
    const error = new Error(`refusing to write an out-of-shape publish journal: ${validation.error}`);
    error.code = validation.error;
    throw error;
  }
  // Never write through a symlink at the target: an attacker-planted symlink
  // could otherwise redirect the write outside the metadata tree.
  assertNotSymlink(path);
  // The temp name is unpredictable (randomBytes), and the open is O_EXCL|O_NOFOLLOW
  // with restrictive mode: an attacker cannot pre-create it to redirect the write,
  // and even a colliding symlink at that exact path fails the open rather than being
  // followed. fstat confirms a regular file before the write, then rename is atomic.
  const tmp = `${path}.${randomBytes(12).toString("hex")}.tmp`;
  const flags = fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW;
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- path derived from a realpath'd git dir + constant basename; exclusive no-follow open
  const fd = openSync(tmp, flags, 0o600);
  try {
    if (!fstatSync(fd).isFile()) {
      throw new Error("the publish journal temporary path is not a regular file");
    }
    writeSync(fd, `${JSON.stringify(record, null, 2)}\n`);
  } finally {
    closeSync(fd);
  }
  // rename is atomic within a filesystem, so a reader never observes a partial write.
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- both paths derived as above
  renameSync(tmp, path);
  return record;
}

// Read and validate the journal. Returns { ok:true, present:false } when absent,
// { ok:true, present:true, record } when valid, and { ok:false, error } when the
// file is unreadable, a symlink, malformed JSON, or out of shape. A failed read
// never deletes the file.
export function readImplementPublishJournal(gitDir) {
  const path = implementPublishJournalPath(gitDir);
  let stat;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- path derived from a realpath'd git dir + constant basename
    stat = lstatSync(path);
  } catch (error) {
    if (error.code === "ENOENT") return { ok: true, present: false };
    return { ok: false, error: "journal_unreadable" };
  }
  if (stat.isSymbolicLink() || !stat.isFile()) {
    return { ok: false, error: "journal_not_regular_file" };
  }
  let parsed;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- path derived as above; regular-file checked
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return { ok: false, error: "journal_unparseable" };
  }
  const validation = validateImplementPublishJournalRecord(parsed);
  if (!validation.ok) return { ok: false, error: validation.error };
  return { ok: true, present: true, record: validation.record };
}

// Remove the journal. Idempotent: a missing journal is success. Callers remove it
// only after the feature head is verified published and the attestation is settled.
export function removeImplementPublishJournal(gitDir) {
  const path = implementPublishJournalPath(gitDir);
  try {
    assertNotSymlink(path);
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- path derived as above
    unlinkSync(path);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
}

function assertNotSymlink(path) {
  let stat;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- path derived from a realpath'd git dir + constant basename
    stat = lstatSync(path);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (stat.isSymbolicLink()) {
    const error = new Error("publish journal path is a symlink; refusing to follow it");
    error.code = "journal_not_regular_file";
    throw error;
  }
}
