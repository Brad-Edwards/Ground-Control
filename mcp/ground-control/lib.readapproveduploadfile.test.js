// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { after, describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import {
  importPackRegistryEntry,
  importReqif,
  importStrictdoc,
  readApprovedUploadFile,
  resolveUploadWorkspaceRoot,
} from "./lib.js";

// ---------------------------------------------------------------------------
// readApprovedUploadFile (#246 — MCP upload path safety)
// ---------------------------------------------------------------------------

describe("readApprovedUploadFile", () => {
  function makeWorkspace() {
    return mkdtempSync(join(tmpdir(), "gc-upload-test-"));
  }

  it("rejects non-string rawPath", () => {
    const ws = makeWorkspace();
    try {
      assert.throws(
        () => readApprovedUploadFile(null, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must be a non-empty string/,
      );
      assert.throws(
        () => readApprovedUploadFile(123, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must be a non-empty string/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects empty string rawPath", () => {
    const ws = makeWorkspace();
    try {
      assert.throws(
        () => readApprovedUploadFile("", { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must be a non-empty string/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects relative path", () => {
    const ws = makeWorkspace();
    try {
      assert.throws(
        () => readApprovedUploadFile("foo.sdoc", { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /absolute/i,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects path containing NUL byte", () => {
    const ws = makeWorkspace();
    try {
      const evilPath = "/tmp/a" + String.fromCharCode(0) + ".sdoc";
      assert.throws(
        () => readApprovedUploadFile(evilPath, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /NUL|null/i,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects wrong extension", () => {
    const ws = makeWorkspace();
    try {
      const target = join(ws, "secret.key");
      writeFileSync(target, "x");
      assert.throws(
        () => readApprovedUploadFile(target, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /\.sdoc/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects when allowedExtensions is empty", () => {
    const ws = makeWorkspace();
    try {
      const target = join(ws, "any.sdoc");
      writeFileSync(target, "x");
      assert.throws(
        () => readApprovedUploadFile(target, { workspaceRoot: ws, allowedExtensions: [], fieldName: "file_path" }),
        /file_path: at least one allowed extension is required/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects non-existent file", () => {
    const ws = makeWorkspace();
    try {
      assert.throws(
        () => readApprovedUploadFile(join(ws, "missing.sdoc"), { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: file does not exist/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects when the leaf is a symlink (even to a regular file inside workspace)", () => {
    const ws = makeWorkspace();
    try {
      const real = join(ws, "real.sdoc");
      writeFileSync(real, "secret-bytes");
      const link = join(ws, "link.sdoc");
      symlinkSync(real, link);
      assert.throws(
        () => readApprovedUploadFile(link, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must not be a symlink/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects an ancestor symlink that escapes the workspace", () => {
    const ws = makeWorkspace();
    const outside = mkdtempSync(join(tmpdir(), "gc-upload-outside-"));
    try {
      const outsideFile = join(outside, "secret.sdoc");
      writeFileSync(outsideFile, "exfiltrated");
      const linkDir = join(ws, "escape");
      symlinkSync(outside, linkDir);
      assert.throws(
        () => readApprovedUploadFile(join(linkDir, "secret.sdoc"), { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must be contained inside the workspace root/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
      rmSync(outside, { recursive: true, force: true });
    }
  });

  it("rejects an absolute path outside the workspace", () => {
    const ws = makeWorkspace();
    const outside = mkdtempSync(join(tmpdir(), "gc-upload-outside-"));
    try {
      const outsideFile = join(outside, "x.sdoc");
      writeFileSync(outsideFile, "no");
      assert.throws(
        () => readApprovedUploadFile(outsideFile, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must be contained inside the workspace root/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
      rmSync(outside, { recursive: true, force: true });
    }
  });

  it("rejects a directory whose name ends with the allowed extension", () => {
    const ws = makeWorkspace();
    try {
      const dirPath = join(ws, "tricky.sdoc");
      mkdirSync(dirPath);
      assert.throws(
        () => readApprovedUploadFile(dirPath, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
        /file_path: must be a regular file/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("matches the extension case-insensitively", () => {
    const ws = makeWorkspace();
    try {
      const target = join(ws, "DOC.SDOC");
      writeFileSync(target, "ok-bytes");
      const result = readApprovedUploadFile(target, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" });
      assert.equal(result.basename, "DOC.SDOC");
      assert.equal(Buffer.from(result.bytes).toString("utf8"), "ok-bytes");
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("returns absPath, basename, and bytes for a regular file inside the workspace", () => {
    const ws = makeWorkspace();
    try {
      const target = join(ws, "good.sdoc");
      writeFileSync(target, "hello");
      const result = readApprovedUploadFile(target, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" });
      assert.equal(result.basename, "good.sdoc");
      assert.equal(Buffer.from(result.bytes).toString("utf8"), "hello");
      assert.ok(result.absPath.endsWith("good.sdoc"));
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects an allowed-extension entry that does not start with a dot", () => {
    const ws = mkdtempSync(join(tmpdir(), "gc-upload-test-"));
    try {
      assert.throws(
        () => readApprovedUploadFile("/tmp/x.json", { workspaceRoot: ws, allowedExtensions: ["json"], fieldName: "file_path" }),
        /start with '\.'/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects an empty-string allowed-extension entry", () => {
    const ws = mkdtempSync(join(tmpdir(), "gc-upload-test-"));
    try {
      assert.throws(
        () => readApprovedUploadFile("/tmp/x.sdoc", { workspaceRoot: ws, allowedExtensions: [""], fieldName: "file_path" }),
        /non-empty string/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects an allowed-extension entry containing a path separator", () => {
    const ws = mkdtempSync(join(tmpdir(), "gc-upload-test-"));
    try {
      assert.throws(
        () => readApprovedUploadFile("/tmp/x.sdoc", { workspaceRoot: ws, allowedExtensions: ["./sdoc"], fieldName: "file_path" }),
        /path separators/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects a dot-only allowed-extension entry", () => {
    const ws = mkdtempSync(join(tmpdir(), "gc-upload-test-"));
    try {
      assert.throws(
        () => readApprovedUploadFile("/tmp/x.sdoc", { workspaceRoot: ws, allowedExtensions: ["."], fieldName: "file_path" }),
        /characters after/,
      );
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("normalizes a permission-denied read into a stable validation error", async () => {
    if (process.getuid && process.getuid() === 0) {
      return; // root reads anything; skip rather than asserting a false expectation
    }
    const { chmodSync } = await import("node:fs");
    const ws = mkdtempSync(join(tmpdir(), "gc-upload-test-"));
    try {
      const target = join(ws, "locked.sdoc");
      writeFileSync(target, "x");
      chmodSync(target, 0o000);
      try {
        assert.throws(
          () => readApprovedUploadFile(target, { workspaceRoot: ws, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
          /file_path: permission denied/,
        );
      } finally {
        chmodSync(target, 0o600); // restore so rmSync can remove it
      }
    } finally {
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("rejects when workspaceRoot is missing or non-string", () => {
    assert.throws(
      () => readApprovedUploadFile("/tmp/x.sdoc", { allowedExtensions: [".sdoc"], fieldName: "file_path" }),
      /file_path: workspaceRoot must be a non-empty string/,
    );
    assert.throws(
      () => readApprovedUploadFile("/tmp/x.sdoc", { workspaceRoot: 123, allowedExtensions: [".sdoc"], fieldName: "file_path" }),
      /file_path: workspaceRoot must be a non-empty string/,
    );
  });
});

describe("resolveUploadWorkspaceRoot", () => {
  function makeGitDir() {
    const dir = mkdtempSync(join(tmpdir(), "gc-upload-resolve-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    return dir;
  }

  it("throws when cwd is not inside a Git repository", async () => {
    const nonGit = mkdtempSync(join(tmpdir(), "gc-upload-nogit-"));
    const prevCwd = process.cwd();
    try {
      process.chdir(nonGit);
      await assert.rejects(
        () => resolveUploadWorkspaceRoot(),
        /workspace root could not be resolved/i,
      );
    } finally {
      process.chdir(prevCwd);
      rmSync(nonGit, { recursive: true, force: true });
    }
  });

  it("returns the Git top-level when cwd is the repository root", async () => {
    const ws = makeGitDir();
    const prevCwd = process.cwd();
    try {
      process.chdir(ws);
      const root = await resolveUploadWorkspaceRoot();
      // realpath the expected to match git's output on macOS /private/var quirks
      const fs = await import("node:fs");
      assert.equal(root, fs.realpathSync(ws));
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("returns the Git top-level (not the subdirectory) when cwd is below the repo root", async () => {
    const ws = makeGitDir();
    const prevCwd = process.cwd();
    try {
      const sub = join(ws, "nested", "deeper");
      mkdirSync(sub, { recursive: true });
      process.chdir(sub);
      const root = await resolveUploadWorkspaceRoot();
      const fs = await import("node:fs");
      assert.equal(root, fs.realpathSync(ws));
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
    }
  });
});

describe("MCP upload action path policies", () => {
  function makeGitWorkspace() {
    const dir = mkdtempSync(join(tmpdir(), "gc-upload-ws-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    return dir;
  }

  it("importStrictdoc rejects a non-.sdoc path", async () => {
    const ws = makeGitWorkspace();
    const prevCwd = process.cwd();
    try {
      process.chdir(ws);
      const target = join(ws, "secret.txt");
      writeFileSync(target, "x");
      await assert.rejects(() => importStrictdoc(target), /\.sdoc/);
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("importReqif rejects a non-.reqif path", async () => {
    const ws = makeGitWorkspace();
    const prevCwd = process.cwd();
    try {
      process.chdir(ws);
      const target = join(ws, "secret.sdoc");
      writeFileSync(target, "x");
      await assert.rejects(() => importReqif(target), /\.reqif/);
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("importPackRegistryEntry rejects a non-.json path", async () => {
    const ws = makeGitWorkspace();
    const prevCwd = process.cwd();
    try {
      process.chdir(ws);
      const target = join(ws, "secret.sdoc");
      writeFileSync(target, "x");
      await assert.rejects(() => importPackRegistryEntry(target, {}), /\.json/);
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("importStrictdoc rejects a symlink leaf even when its target is a real .sdoc inside the workspace", async () => {
    const ws = makeGitWorkspace();
    const prevCwd = process.cwd();
    try {
      process.chdir(ws);
      const real = join(ws, "real.sdoc");
      writeFileSync(real, "x");
      const link = join(ws, "link.sdoc");
      symlinkSync(real, link);
      await assert.rejects(() => importStrictdoc(link), /symlink/i);
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
    }
  });

  it("importStrictdoc rejects an absolute path outside the workspace", async () => {
    const ws = makeGitWorkspace();
    const outside = mkdtempSync(join(tmpdir(), "gc-upload-outside-"));
    const prevCwd = process.cwd();
    try {
      process.chdir(ws);
      const target = join(outside, "outside.sdoc");
      writeFileSync(target, "x");
      await assert.rejects(() => importStrictdoc(target), /outside|workspace|contain/i);
    } finally {
      process.chdir(prevCwd);
      rmSync(ws, { recursive: true, force: true });
      rmSync(outside, { recursive: true, force: true });
    }
  });

  it("importStrictdoc refuses when the MCP cwd is not in a Git repository", async () => {
    const nonGit = mkdtempSync(join(tmpdir(), "gc-upload-nogit-"));
    const prevCwd = process.cwd();
    try {
      process.chdir(nonGit);
      const target = join(nonGit, "x.sdoc");
      writeFileSync(target, "x");
      await assert.rejects(
        () => importStrictdoc(target),
        /workspace root could not be resolved/i,
      );
    } finally {
      process.chdir(prevCwd);
      rmSync(nonGit, { recursive: true, force: true });
    }
  });
});
