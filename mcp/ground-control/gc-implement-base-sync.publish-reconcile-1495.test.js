// Real temporary-repository coverage for interrupted-publish reconciliation
// (issue #1495). Mocked SHA strings cannot prove Git operation-state handling, so
// these drive the reconciliation against a live git checkout with a real journal,
// a simulated staged merge (MERGE_HEAD), and a dirty tree.

import { execFile as execFileCb } from "node:child_process";
import { promisify } from "node:util";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  reconcileInterruptedPublish,
  resolvePublishGitDir,
  readImplementPublishJournal,
  writeImplementPublishJournal,
} from "./lib.js";

const execFile = promisify(execFileCb);
const BRANCH = "1495-publish-recovery";
const ISSUE = 1495;

async function initRepo() {
  const dir = mkdtempSync(join(tmpdir(), "gc-reconcile-"));
  const git = (...args) => execFile("git", ["-C", dir, ...args]);
  await git("init", "-q");
  await git("config", "user.email", "t@example.test");
  await git("config", "user.name", "Test");
  await git("config", "commit.gpgSign", "false");
  writeFileSync(join(dir, "a.txt"), "one\n");
  await git("add", "-A");
  await git("commit", "-q", "-m", "c1");
  await git("checkout", "-q", "-b", BRANCH);
  return { dir, git };
}

function journalFields(overrides = {}) {
  return {
    issue_number: ISSUE,
    branch: BRANCH,
    base_branch: "dev",
    pre_publish_head: null,
    phase: "merge_staged",
    ...overrides,
  };
}

async function reconcile(dir, gitDir, overrides = {}) {
  return reconcileInterruptedPublish({
    repoRoot: dir,
    gitDir,
    branchName: BRANCH,
    issueNumber: ISSUE,
    commandRunner: execFile,
    ...overrides,
  });
}

describe("interrupted-publish reconciliation on a real checkout (#1495)", () => {
  it("proceeds when no journal is present", async () => {
    const { dir } = await initRepo();
    try {
      const gitDir = await resolvePublishGitDir(dir, execFile);
      assert.deepEqual(await reconcile(dir, gitDir), { proceed: true });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("clears a spent journal and proceeds on a clean same-attempt checkout", async () => {
    const { dir } = await initRepo();
    try {
      const gitDir = await resolvePublishGitDir(dir, execFile);
      writeImplementPublishJournal(gitDir, journalFields({ phase: "initializing" }));
      assert.deepEqual(await reconcile(dir, gitDir), { proceed: true });
      assert.equal(readImplementPublishJournal(gitDir).present, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses without mutating when a staged merge is present", async () => {
    const { dir, git } = await initRepo();
    try {
      const gitDir = await resolvePublishGitDir(dir, execFile);
      writeImplementPublishJournal(gitDir, journalFields());
      const head = (await git("rev-parse", "HEAD")).stdout.trim();
      // A real MERGE_HEAD control file: git resolves it exactly as a live merge.
      writeFileSync(join(gitDir, "MERGE_HEAD"), `${head}\n`);
      const result = await reconcile(dir, gitDir);
      assert.ok(result.resolved);
      assert.equal(result.resolved.error, "implement_publish_interrupted_merge_present");
      assert.equal(result.resolved.agent_required, true);
      assert.equal(result.resolved.recovery.merge_head_present, true);
      // The journal is preserved for inspection, never deleted here.
      assert.equal(readImplementPublishJournal(gitDir).present, true);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("clears a stale journal and proceeds on a dirty tree with no merge in progress", async () => {
    // A pre-commit failure leaves exactly this state (the user's staged work, no
    // MERGE_HEAD). It is ordinary repair-and-retry territory, so reconciliation
    // must clear the spent journal and proceed rather than refuse and demand a
    // manual journal removal.
    const { dir } = await initRepo();
    try {
      const gitDir = await resolvePublishGitDir(dir, execFile);
      writeImplementPublishJournal(gitDir, journalFields());
      writeFileSync(join(dir, "a.txt"), "changed\n");
      const result = await reconcile(dir, gitDir);
      assert.deepEqual(result, { proceed: true });
      assert.equal(readImplementPublishJournal(gitDir).present, false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("returns the base-sync retry handle when the staged merge matches its journal", async () => {
    const { dir, git } = await initRepo();
    try {
      // A second commit so the feature HEAD and the merged base differ.
      writeFileSync(join(dir, "b.txt"), "two\n");
      await git("add", "-A");
      await git("commit", "-q", "-m", "c2");
      const head = (await git("rev-parse", "HEAD")).stdout.trim();
      const base = (await git("rev-parse", "HEAD^")).stdout.trim();
      const gitDir = await resolvePublishGitDir(dir, execFile);
      const recordId = "d".repeat(32);
      writeImplementPublishJournal(gitDir, journalFields({
        record_id: recordId,
        published_pre_sync_head: head,
        fetched_base_sha: base,
        expected_merge_head: base,
      }));
      writeFileSync(join(gitDir, "MERGE_HEAD"), `${base}\n`);
      const result = await reconcile(dir, gitDir);
      assert.equal(result.resolved.error, "implement_publish_interrupted_merge_present");
      assert.equal(result.resolved.recovery.matches_recorded_attempt, true);
      assert.deepEqual(result.resolved.retry_input, {
        record_id: recordId,
        pre_sync_sha: head,
        fetched_base_sha: base,
        outcome: "merged_conflicts_resolved",
      });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses a journal that belongs to a different issue or branch", async () => {
    const { dir } = await initRepo();
    try {
      const gitDir = await resolvePublishGitDir(dir, execFile);
      writeImplementPublishJournal(gitDir, journalFields({ issue_number: 4242, branch: "4242-other" }));
      const result = await reconcile(dir, gitDir);
      assert.equal(result.resolved.error, "implement_publish_recovery_journal_foreign");
      assert.equal(readImplementPublishJournal(gitDir).present, true);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("refuses and preserves a corrupt journal", async () => {
    const { dir } = await initRepo();
    try {
      const gitDir = await resolvePublishGitDir(dir, execFile);
      writeFileSync(join(gitDir, "gc-implement-publish-journal.json"), "{ not json");
      const result = await reconcile(dir, gitDir);
      assert.equal(result.resolved.error, "implement_publish_recovery_journal_corrupt");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
