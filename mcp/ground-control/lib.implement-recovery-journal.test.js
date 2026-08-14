// Versioned write-ahead publish recovery journal (issue #1495).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, statSync, writeFileSync, symlinkSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  IMPLEMENT_PUBLISH_JOURNAL_BASENAME,
  IMPLEMENT_PUBLISH_JOURNAL_SCHEMA,
  implementPublishJournalPath,
  readImplementPublishJournal,
  removeImplementPublishJournal,
  writeImplementPublishJournal,
} from "./lib.js";

function tempGitDir() {
  return mkdtempSync(join(tmpdir(), "gc-journal-test-"));
}

const OID = "a".repeat(40);
const BASE_OID = "b".repeat(40);

function baseFields() {
  return {
    record_id: "c".repeat(32),
    issue_number: 1495,
    branch: "1495-publish-hang-recovery",
    base_branch: "dev",
    pre_publish_head: OID,
    published_pre_sync_head: OID,
    fetched_base_sha: BASE_OID,
    expected_merge_head: BASE_OID,
    phase: "merge_staged",
  };
}

describe("implement publish recovery journal (#1495)", () => {
  it("round-trips a write then read with the closed shape", () => {
    const dir = tempGitDir();
    try {
      const written = writeImplementPublishJournal(dir, baseFields());
      assert.equal(written.schema, IMPLEMENT_PUBLISH_JOURNAL_SCHEMA);
      const read = readImplementPublishJournal(dir);
      assert.equal(read.ok, true);
      assert.equal(read.present, true);
      assert.equal(read.record.issue_number, 1495);
      assert.equal(read.record.expected_merge_head, BASE_OID);
      assert.equal(read.record.classification, "in_progress");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("reports absent when no journal exists", () => {
    const dir = tempGitDir();
    try {
      const read = readImplementPublishJournal(dir);
      assert.equal(read.ok, true);
      assert.equal(read.present, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("writes with 0600 permissions", () => {
    const dir = tempGitDir();
    try {
      writeImplementPublishJournal(dir, baseFields());
      const mode = statSync(join(dir, IMPLEMENT_PUBLISH_JOURNAL_BASENAME)).mode & 0o777;
      assert.equal(mode, 0o600);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("preserves created_at across updates but advances updated_at", () => {
    const dir = tempGitDir();
    try {
      let t = 0;
      const clock = () => new Date(1700000000000 + (t++) * 1000).toISOString();
      const first = writeImplementPublishJournal(dir, baseFields(), { now: clock });
      const second = writeImplementPublishJournal(
        dir,
        { ...baseFields(), phase: "merge_committed" },
        { now: clock },
      );
      assert.equal(second.created_at, first.created_at);
      assert.notEqual(second.updated_at, first.updated_at);
      assert.equal(readImplementPublishJournal(dir).record.phase, "merge_committed");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("preserves earlier identities when a later phase write omits them", () => {
    const dir = tempGitDir();
    try {
      writeImplementPublishJournal(dir, baseFields({ phase: "initializing" }));
      // A phase advance that carries only the phase must not reset the record ID,
      // heads, or fetched base recorded by the earlier mutating step.
      writeImplementPublishJournal(dir, { phase: "feature_committed" });
      const read = readImplementPublishJournal(dir);
      assert.equal(read.record.phase, "feature_committed");
      assert.equal(read.record.record_id, "c".repeat(32));
      assert.equal(read.record.pre_publish_head, OID);
      assert.equal(read.record.fetched_base_sha, BASE_OID);
      assert.equal(read.record.issue_number, 1495);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses to write an out-of-shape record", () => {
    const dir = tempGitDir();
    try {
      assert.throws(() => writeImplementPublishJournal(dir, { ...baseFields(), phase: "not-a-phase" }));
      assert.throws(() => writeImplementPublishJournal(dir, { ...baseFields(), fetched_base_sha: "nothex" }));
      // Nothing was persisted by the refused writes.
      assert.equal(readImplementPublishJournal(dir).present, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("fails closed on an unknown schema, unknown field, and malformed JSON — without deleting", () => {
    const dir = tempGitDir();
    const path = join(dir, IMPLEMENT_PUBLISH_JOURNAL_BASENAME);
    try {
      writeFileSync(path, JSON.stringify({ schema: "other/v9" }), "utf8");
      assert.equal(readImplementPublishJournal(dir).error, "journal_schema_unknown");

      writeFileSync(path, JSON.stringify({ schema: IMPLEMENT_PUBLISH_JOURNAL_SCHEMA, sneaky: 1 }), "utf8");
      assert.equal(readImplementPublishJournal(dir).error, "journal_unknown_field");

      writeFileSync(path, "{ not json", "utf8");
      assert.equal(readImplementPublishJournal(dir).error, "journal_unparseable");
      // The corrupt file is left in place for inspection.
      assert.ok(readFileSync(path, "utf8").length > 0);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses to read or write through a symlink at the journal path", () => {
    const dir = tempGitDir();
    const outside = tempGitDir();
    const path = join(dir, IMPLEMENT_PUBLISH_JOURNAL_BASENAME);
    try {
      symlinkSync(join(outside, "target.json"), path);
      assert.equal(readImplementPublishJournal(dir).error, "journal_not_regular_file");
      assert.throws(() => writeImplementPublishJournal(dir, baseFields()));
    } finally {
      rmSync(dir, { recursive: true, force: true });
      rmSync(outside, { recursive: true, force: true });
    }
  });

  it("remove is idempotent", () => {
    const dir = tempGitDir();
    try {
      writeImplementPublishJournal(dir, baseFields());
      removeImplementPublishJournal(dir);
      removeImplementPublishJournal(dir);
      assert.equal(readImplementPublishJournal(dir).present, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("derives the journal path inside the given git dir", () => {
    const dir = tempGitDir();
    try {
      assert.equal(implementPublishJournalPath(dir).endsWith(`/${IMPLEMENT_PUBLISH_JOURNAL_BASENAME}`), true);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
