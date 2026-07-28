// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  REVIEW_NOTES_MAX,
  aggregateReviewSlices,
  parseCodexReviewFindingsTail,
  validateFindingPath,
} from "./lib.js";

describe("aggregateReviewSlices", () => {
  const finding = (path, line, title, extra = {}) => ({
    path,
    line,
    title,
    body: `body for ${title}`,
    classification: "one-off",
    sweep_evidence: "swept",
    ...extra,
  });
  const slice = (envelope, body = "") => ({ envelope, body, findings: envelope?.blocking ?? [] });

  it("passes a single slice through unchanged", () => {
    const env = { verdict: "ship", architectural_read: "Looks fine.", blocking: [], notes: [] };
    const agg = aggregateReviewSlices([slice(env, "prose")]);
    assert.equal(agg.slices_completed, 1);
    assert.equal(agg.envelope.verdict, "ship");
    assert.equal(agg.envelope.architectural_read, "Looks fine.");
    assert.equal(agg.body, "prose");
  });

  it("labels each slice's architectural read when there is more than one slice", () => {
    const agg = aggregateReviewSlices([
      slice({ verdict: "ship", architectural_read: "First half is fine.", blocking: [], notes: [] }),
      slice({ verdict: "ship", architectural_read: "Second half is fine.", blocking: [], notes: [] }),
    ]);
    assert.equal(agg.slices_completed, 2);
    assert.match(agg.envelope.architectural_read, /Slice 1\/2/);
    assert.match(agg.envelope.architectural_read, /Slice 2\/2/);
    assert.match(agg.envelope.architectural_read, /Second half is fine\./);
  });

  it("unions blocking findings across slices and dedups identical sites", () => {
    const dup = finding("Foo.java", 10, "Bypasses the canonical envelope");
    const agg = aggregateReviewSlices([
      slice({ verdict: "ship-with-fixes", architectural_read: "a", blocking: [dup], notes: [] }),
      slice({
        verdict: "ship-with-fixes",
        architectural_read: "b",
        blocking: [dup, finding("Bar.java", 3, "Second problem")],
        notes: [],
      }),
    ]);
    assert.equal(agg.envelope.blocking.length, 2);
    assert.equal(agg.envelope.verdict, "ship-with-fixes");
  });

  it("never reports ship when any slice produced a blocking finding", () => {
    const agg = aggregateReviewSlices([
      slice({ verdict: "ship", architectural_read: "clean here", blocking: [], notes: [] }),
      slice({
        verdict: "ship-with-fixes",
        architectural_read: "problem here",
        blocking: [finding("Foo.java", 1, "Real problem")],
        notes: [],
      }),
    ]);
    assert.equal(agg.envelope.verdict, "ship-with-fixes");
    assert.equal(agg.envelope.blocking.length, 1);
  });

  it("preserves don't-ship when a slice raised a structural blocker", () => {
    const agg = aggregateReviewSlices([
      slice({ verdict: "ship", architectural_read: "clean", blocking: [], notes: [] }),
      slice({
        verdict: "don't-ship",
        architectural_read: "structural",
        blocking: [finding("Foo.java", 1, "Missing boundary", { structural_blocker: true })],
        notes: [],
      }),
    ]);
    assert.equal(agg.envelope.verdict, "don't-ship");
  });

  it("downgrades don't-ship to ship-with-fixes when no structural blocker survives dedup", () => {
    const agg = aggregateReviewSlices([
      slice({
        verdict: "don't-ship",
        architectural_read: "claimed structural",
        blocking: [finding("Foo.java", 1, "Ordinary problem")],
        notes: [],
      }),
    ]);
    assert.equal(agg.envelope.verdict, "ship-with-fixes");
  });

  it("re-caps the union of notes at the workflow notes cap", () => {
    const agg = aggregateReviewSlices([
      slice({ verdict: "ship", architectural_read: "a", blocking: [], notes: [{ text: "n1" }, { text: "n2" }] }),
      slice({ verdict: "ship", architectural_read: "b", blocking: [], notes: [{ text: "n3" }] }),
    ]);
    assert.equal(agg.envelope.notes.length, REVIEW_NOTES_MAX);
  });

  it("reports fewer completed slices when a slice failed to parse", () => {
    const agg = aggregateReviewSlices([
      slice({ verdict: "ship", architectural_read: "a", blocking: [], notes: [] }),
      { envelope: null, body: "unparseable", findings: [] },
    ]);
    assert.equal(agg.slices_completed, 1);
    assert.equal(agg.slices_total, 2);
  });
});

describe("validateFindingPath", () => {
  // Tests use a synthetic repoRoot (the path need not exist on disk because the
  // validator is lexical — it never opens the file). This is intentional:
  // codex review findings frequently reference newly-added files in the diff
  // that may or may not exist in the working tree at validation time.
  const repoRoot = "/tmp/gc-test-repo";

  it("accepts a plain repo-relative path", () => {
    assert.equal(validateFindingPath("src/foo.java", repoRoot), "src/foo.java");
  });

  it("accepts a deeply nested repo-relative path", () => {
    assert.equal(
      validateFindingPath("backend/src/main/java/com/keplerops/Foo.java", repoRoot),
      "backend/src/main/java/com/keplerops/Foo.java",
    );
  });

  it("rejects non-string input", () => {
    assert.throws(() => validateFindingPath(null, repoRoot), /must be a non-empty string/);
    assert.throws(() => validateFindingPath(42, repoRoot), /must be a non-empty string/);
    assert.throws(() => validateFindingPath(undefined, repoRoot), /must be a non-empty string/);
  });

  it("rejects empty / whitespace strings", () => {
    assert.throws(() => validateFindingPath("", repoRoot), /must be a non-empty string/);
    assert.throws(() => validateFindingPath("   ", repoRoot), /must be a non-empty string/);
  });

  it("rejects absolute paths", () => {
    assert.throws(() => validateFindingPath("/etc/passwd", repoRoot), /must be a repo-relative path/);
    assert.throws(() => validateFindingPath("/tmp/gc-test-repo/src/foo.java", repoRoot), /must be a repo-relative path/);
  });

  it("rejects parent-directory traversal segments", () => {
    // The new lexical `..` check fires before the containment check; either
    // message proves the path was rejected for the right reason.
    const traversalRejection = /\.\.|inside the repository root/;
    assert.throws(() => validateFindingPath("../etc/passwd", repoRoot), traversalRejection);
    assert.throws(() => validateFindingPath("foo/../../bar", repoRoot), traversalRejection);
    assert.throws(() => validateFindingPath("..", repoRoot), traversalRejection);
  });

  it("rejects '..' as ANY segment even when normalization stays inside the repo", () => {
    // Defense-in-depth: a path like 'src/../README.md' lexically contains a
    // `..` segment but normalizes back inside the repo. The schema/README
    // documents 'no `..` segments' precisely so codex never emits this shape;
    // the validator must reject it before normalization, not after, to match
    // the documented contract and avoid POSTs against odd-looking paths.
    assert.throws(() => validateFindingPath("src/../README.md", repoRoot), /\.\./);
    assert.throws(() => validateFindingPath("a/b/../c", repoRoot), /\.\./);
  });
});

describe("parseCodexReviewFindingsTail (verdict envelope, #931)", () => {
  const repoRoot = "/tmp/gc-test-repo";

  // Wrap a `blocking` findings array in the verdict envelope and the new
  // ===REVIEW===…===END=== tail. Tests that exercise the per-finding
  // validation still pass arrays here; the wrapper supplies the envelope
  // boilerplate (verdict + architectural_read) that the new contract requires.
  function makeReviewTail(blocking, { verdict, architectural_read, prelude = "", tail = "" } = {}) {
    const computedVerdict = verdict ?? (blocking.length === 0 ? "ship" : "ship-with-fixes");
    const envelope = {
      verdict: computedVerdict,
      architectural_read: architectural_read ?? "Reviewed the diff for shape and seam.",
      blocking,
    };
    return `${prelude}===REVIEW===\n${JSON.stringify(envelope)}\n===END===${tail}`;
  }

  // Convenience: a valid one-off finding requires sweep_evidence (#931).
  const SWEEP = "grepped the diff and adjacent code; no other instances.";

  it("parses a well-formed REVIEW envelope and strips the tail block from the body", () => {
    const stdout = makeReviewTail(
      [
        { path: "src/foo.java", line: 42, title: "Missing input validation", body: "The handler does not validate the `name` parameter.", classification: "one-off", sweep_evidence: SWEEP },
        { path: "src/bar.java", line: 88, title: "Bypass of existing helper", body: "Uses raw JdbcTemplate.", classification: "class", category: { shape: "controller method bypassing scoped repository", instances: ["src/bar.java:88", "src/baz.java:140"] } },
      ],
      { prelude: "**Findings**\n\n- src/foo.java:42 missing validation\n- src/bar.java:88 bypass\n\n", tail: "\n" },
    );
    const { findings, body, envelope } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.equal(findings.length, 2);
    assert.equal(findings[0].classification, "one-off");
    assert.equal(findings[0].sweep_evidence, SWEEP);
    assert.equal(findings[1].classification, "class");
    assert.deepEqual(findings[1].category, {
      shape: "controller method bypassing scoped repository",
      instances: ["src/bar.java:88", "src/baz.java:140"],
    });
    assert.equal(envelope.verdict, "ship-with-fixes");
    assert.ok(envelope.architectural_read.length > 0);
    assert.ok(!body.includes("===REVIEW==="));
    assert.ok(!body.includes("===END==="));
    assert.ok(body.includes("**Findings**"));
  });

  it("requires a `classification` on every blocking finding", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y" }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /classification/);
  });

  it("rejects an unknown `classification` value", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "minor" }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /classification/);
  });

  it("requires `category` {shape, instances} when classification is class", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "class" }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /category/);
  });

  it("rejects an empty `category.instances` array", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "class", category: { shape: "a recurring pattern", instances: [] } }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /instances/);
  });

  it("rejects a `category` on a one-off finding", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP, category: { shape: "x", instances: ["src/foo.java:42"] } }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /one-off/);
  });

  it("parses an empty blocking array as verdict='ship'", () => {
    const stdout = makeReviewTail([], { prelude: "Reviewed the diff. No issues found.\n\n", tail: "\n" });
    const { findings, body, envelope } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.deepEqual(findings, []);
    assert.equal(envelope.verdict, "ship");
    assert.ok(body.includes("No issues found"));
    assert.ok(!body.includes("===REVIEW==="));
  });

  it("rejects line: null until file-level posting is implemented", () => {
    const stdout = makeReviewTail(
      [{ path: "src/foo.java", line: null, title: "File-scope concern", body: "Whole-file note.", classification: "one-off", sweep_evidence: SWEEP }],
      { prelude: "prose\n" },
    );
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /line/);
  });

  it("throws when the REVIEW block is missing", () => {
    assert.throws(
      () => parseCodexReviewFindingsTail("only prose, no tail block here", repoRoot),
      /===REVIEW===/,
    );
  });

  it("throws when the JSON is malformed", () => {
    const stdout = "===REVIEW===\n{not valid json}\n===END===";
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /JSON|parse/i);
  });

  it("throws when the JSON is not an envelope object", () => {
    const stdout = '===REVIEW===\n["array, not object"]\n===END===';
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /object|envelope/i);
  });

  it("requires a non-empty architectural_read", () => {
    const envelope = { verdict: "ship", architectural_read: "", blocking: [] };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /architectural_read/);
  });

  it("rejects verdict='ship' with non-empty blocking[]", () => {
    const envelope = {
      verdict: "ship",
      architectural_read: "Reviewed.",
      blocking: [{ path: "src/foo.java", line: 1, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }],
    };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /verdict='ship'.*inconsistent/);
  });

  it("rejects verdict='ship-with-fixes' with empty blocking[]", () => {
    const envelope = { verdict: "ship-with-fixes", architectural_read: "Reviewed.", blocking: [] };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /requires non-empty blocking/);
  });

  it("rejects verdict='don't-ship' without a structural blocker", () => {
    const envelope = {
      verdict: "don't-ship",
      architectural_read: "Bad shape.",
      blocking: [{ path: "src/foo.java", line: 1, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }],
    };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /structural blocker/);
  });

  it("accepts verdict='don't-ship' when a class finding provides structural evidence", () => {
    const envelope = {
      verdict: "don't-ship",
      architectural_read: "Class-level boundary violation.",
      blocking: [{ path: "src/foo.java", line: 1, title: "x", body: "y", classification: "class", category: { shape: "missing auth check", instances: ["src/foo.java:1", "src/bar.java:2"] } }],
    };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    const { envelope: parsed } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.equal(parsed.verdict, "don't-ship");
  });

  it("caps notes at REVIEW_NOTES_MAX (2)", () => {
    const envelope = {
      verdict: "ship",
      architectural_read: "Reviewed.",
      blocking: [],
      notes: [{ text: "a" }, { text: "b" }, { text: "c" }],
    };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /notes.*cap/i);
  });

  it("truncates an over-long note instead of discarding the whole review (aptl #293)", () => {
    // An LLM reviewer cannot be relied on to honour a hard char budget
    // for advisory prose. A note that overruns REVIEW_NOTE_TEXT_MAX is
    // truncated (ellipsis-terminated) so a completed review still
    // parses, rather than throwing and losing the entire review.
    const envelope = {
      verdict: "ship",
      architectural_read: "Reviewed.",
      blocking: [],
      notes: [{ text: "x".repeat(450) }],
    };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    const { envelope: parsed } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.equal(parsed.notes.length, 1);
    assert.equal(parsed.notes[0].text.length, 300);
    assert.ok(parsed.notes[0].text.endsWith("…"));
  });

  it("truncates over-long finding prose (title/body/sweep_evidence) instead of discarding the review (aptl #293)", () => {
    // Same brittleness as notes[]: an LLM reviewer overrunning a hard
    // char budget on a finding's prose fields must not discard the
    // whole completed review. Structural fields still throw.
    const stdout = makeReviewTail([
      {
        path: "src/foo.java",
        line: 42,
        title: "T".repeat(900),
        body: "B".repeat(70000),
        classification: "one-off",
        sweep_evidence: "S".repeat(900),
      },
    ]);
    const { envelope } = parseCodexReviewFindingsTail(stdout, repoRoot);
    const f = envelope.blocking[0];
    assert.equal(f.title.length, 200);
    assert.ok(f.title.endsWith("…"));
    assert.equal(f.body.length, 65535 - 213 - 800);
    assert.ok(f.body.endsWith("…"));
    assert.equal(f.sweep_evidence.length, 500);
    assert.ok(f.sweep_evidence.endsWith("…"));
  });

  it("truncates an over-long category.shape on a class finding (aptl #293)", () => {
    const stdout = makeReviewTail([
      {
        path: "src/foo.java",
        line: 42,
        title: "x",
        body: "y",
        classification: "class",
        category: { shape: "C".repeat(600), instances: ["src/foo.java:42"] },
      },
    ]);
    const { envelope } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.equal(envelope.blocking[0].category.shape.length, 300);
    assert.ok(envelope.blocking[0].category.shape.endsWith("…"));
  });

  it("requires sweep_evidence on one-off findings (#931)", () => {
    // Note: this test deliberately omits sweep_evidence to exercise the
    // required-field check.
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "one-off" }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /sweep_evidence/);
  });

  it("rejects sweep_evidence on class findings", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "class", sweep_evidence: SWEEP, category: { shape: "pattern", instances: ["src/foo.java:42", "src/bar.java:1"] } }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /sweep_evidence/);
  });

  it("accepts structural_blocker=true on a one-off", () => {
    const envelope = {
      verdict: "don't-ship",
      architectural_read: "Missing security boundary.",
      blocking: [{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP, structural_blocker: true }],
    };
    const stdout = `===REVIEW===\n${JSON.stringify(envelope)}\n===END===`;
    const { findings } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.equal(findings[0].structural_blocker, true);
  });

  it("rejects structural_blocker=true on a class finding", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "class", structural_blocker: true, category: { shape: "pattern", instances: ["src/foo.java:42", "src/bar.java:1"] } }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /structural_blocker.*implicit/);
  });

  it("throws when a finding is missing `path`", () => {
    const stdout = makeReviewTail([{ line: 42, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /path/);
  });

  it("throws when a finding is missing `title`", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /title/);
  });

  it("throws when a finding is missing `body`", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /body/);
  });

  it("throws when a finding is missing `line`", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /line/);
  });

  it("throws when `line` is zero or negative", () => {
    for (const badLine of [0, -1, -42]) {
      const stdout = makeReviewTail([{ path: "src/foo.java", line: badLine, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
      assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /line/);
    }
  });

  it("throws when `line` is not an integer", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: "42", title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /line/);
  });

  it("throws when `title` is empty", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /title/);
  });

  it("throws when `body` is empty", () => {
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /body/);
  });

  it("accepts a body up to the cap that leaves room for the rendered prefix + classification note", () => {
    const safeLen = 64522;
    const stdout = makeReviewTail([{ path: "src/foo.java", line: 42, title: "x".repeat(200), body: "y".repeat(safeLen), classification: "one-off", sweep_evidence: SWEEP }]);
    const { findings } = parseCodexReviewFindingsTail(stdout, repoRoot);
    assert.equal(findings[0].body.length, safeLen);
  });

  it("validates each `category.instances` entry and requires the finding's own site", () => {
    const mk = (instances) =>
      makeReviewTail([{ path: "src/foo.java", line: 42, title: "x", body: "y", classification: "class", category: { shape: "a recurring pattern", instances } }]);
    assert.throws(() => parseCodexReviewFindingsTail(mk(["later"]), repoRoot), /<path>:<line>|instances/);
    assert.throws(() => parseCodexReviewFindingsTail(mk(["../etc/passwd:1", "src/foo.java:42"]), repoRoot), /path|traversal|instances/i);
    assert.throws(() => parseCodexReviewFindingsTail(mk(["src/bar.java:7"]), repoRoot), /own site/i);
    const { findings } = parseCodexReviewFindingsTail(mk(["src/foo.java:42", "src/foo.java:42", "src/bar.java:7"]), repoRoot);
    assert.deepEqual(findings[0].category.instances, ["src/foo.java:42", "src/bar.java:7"]);
  });

  it("throws when a `path` escapes the repo via traversal", () => {
    const stdout = makeReviewTail([{ path: "../etc/passwd", line: 1, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /\.\.|repository root|repo-relative/);
  });

  it("throws when a `path` is absolute", () => {
    const stdout = makeReviewTail([{ path: "/etc/passwd", line: 1, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP }]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /repo-relative/);
  });

  it("throws when a non-string value is passed", () => {
    assert.throws(() => parseCodexReviewFindingsTail(null, repoRoot), /not a string/);
    assert.throws(() => parseCodexReviewFindingsTail(undefined, repoRoot), /not a string/);
  });

  it("includes the finding index in the error so codex output is debuggable", () => {
    const stdout = makeReviewTail([
      { path: "src/foo.java", line: 42, title: "x", body: "y", classification: "one-off", sweep_evidence: SWEEP },
      { path: "src/bar.java", line: 99, title: "ok", body: "", classification: "one-off", sweep_evidence: SWEEP }, // bad: empty body
    ]);
    assert.throws(() => parseCodexReviewFindingsTail(stdout, repoRoot), /\b1\b/); // finding index 1
  });
});
