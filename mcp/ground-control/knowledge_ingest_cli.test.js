import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { main } from "./knowledge_ingest_cli.js";

// The CLI is deliberately thin — argv → runIngest → exit code. We do not
// re-exercise the whole ingest transaction here; the knowledge_ingest
// test suite already covers that. These tests just verify the argv
// plumbing and the two exit-code paths (argv error vs runtime error).

describe("knowledge_ingest_cli argv parsing", () => {
  it("exits 2 on missing required arguments", async () => {
    const code = await main([]);
    assert.equal(code, 2);
  });

  it("exits 2 on unknown arguments", async () => {
    const code = await main([
      "--repo", "/tmp/x",
      "--inbox-file", "/tmp/x/item.md",
      "--knowledge-dir", "docs/knowledge",
      "--knowledge-schema", "docs/knowledge/SCHEMA.md",
      "--knowledge-inbox", "docs/knowledge/inbox",
      "--bogus", "value",
    ]);
    assert.equal(code, 2);
  });

  it("exits 2 when a flag is missing its value", async () => {
    const code = await main(["--repo"]);
    assert.equal(code, 2);
  });

  it("exits 1 on a runtime failure (nonexistent repo)", async () => {
    // All argv is well-formed, but the repo path does not exist, so
    // runIngest throws and the CLI returns a non-zero (non-argv) code.
    const code = await main([
      "--repo", "/does/not/exist/for/real",
      "--inbox-file", "/does/not/exist/for/real/item.md",
      "--knowledge-dir", "docs/knowledge",
      "--knowledge-schema", "docs/knowledge/SCHEMA.md",
      "--knowledge-inbox", "docs/knowledge/inbox",
    ]);
    assert.equal(code, 1);
  });
});
