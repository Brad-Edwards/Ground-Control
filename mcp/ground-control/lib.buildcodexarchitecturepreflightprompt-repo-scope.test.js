// Architecture preflight (`--sandbox workspace-write -C <repo>`) does not
// confine reads to the repo — a codex-issued grep with no path argument
// searched `/` and ran orphaned for 10+ days (issue #1518). `-C` and
// `workspace-write` are not a host read boundary, so the prompt must say so
// explicitly as a guard against accidental unscoped inspection.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildCodexArchitecturePreflightPrompt } from "./lib/codex-workflow-3.js";

describe("buildCodexArchitecturePreflightPrompt — repository search scope", () => {
  it("states that repository-wide inspection means the current repository only", () => {
    const prompt = buildCodexArchitecturePreflightPrompt({ issueContext: { title: "x", body: "" } });
    assert.match(prompt, /current repository only/i);
  });

  it("explicitly forbids searching outside the repository root", () => {
    const prompt = buildCodexArchitecturePreflightPrompt({ issueContext: { title: "x", body: "" } });
    assert.match(prompt, /never (?:recursively )?search .*(?:\/|parent directory|home directory)/i);
  });

  it("is present on both the requirement-backed and requirement-free prompt shapes", () => {
    const requirementFree = buildCodexArchitecturePreflightPrompt({ issueContext: { title: "x", body: "" } });
    const requirementBacked = buildCodexArchitecturePreflightPrompt({
      requirement: { uid: "GC-X001", statement: "..." },
      issueContext: { title: "x", body: "" },
    });
    assert.match(requirementFree, /current repository only/i);
    assert.match(requirementBacked, /current repository only/i);
  });
});
