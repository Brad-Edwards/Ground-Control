// Coverage for the architecture-vocabulary config normalizers in
// lib/repo-vocabulary.js. Only normalizeArchitectureConfig is exported (via the
// lib.js barrel); it drives the internal validateVocabulary* helpers and
// normalizeArchitectureVocabularyConfig, so every guard/error branch is
// exercised through it by feeding invalid `architecture.vocabulary` configs.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { normalizeArchitectureConfig } from "./lib.js";

// Wrap a vocabulary payload as a full architecture config and normalize it.
function normalizeVocab(vocabulary) {
  return normalizeArchitectureConfig({ vocabulary });
}

// Assert the result is a failure carrying `message` verbatim among its errors.
function assertError(result, message) {
  assert.equal(result.ok, false);
  assert.ok(Array.isArray(result.errors), "errors must be an array");
  assert.ok(
    result.errors.includes(message),
    `expected errors to include:\n  ${message}\ngot:\n  ${JSON.stringify(result.errors)}`,
  );
}

describe("normalizeArchitectureConfig vocabulary.patterns validation", () => {
  it("rejects a non-list patterns value (line 28/29)", () => {
    const result = normalizeVocab({ patterns: "not-a-list" });
    assertError(result, "architecture.vocabulary.patterns must be a list when set");
  });

  it("rejects a pattern entry that is not a mapping (line 35/36)", () => {
    const result = normalizeVocab({ patterns: [123] });
    assertError(result, "architecture.vocabulary.patterns[0] must be a mapping");
  });

  it("rejects a pattern with a blank applies_to (line 47/48)", () => {
    const result = normalizeVocab({ patterns: [{ name: "P", applies_to: "" }] });
    assertError(
      result,
      "architecture.vocabulary.patterns[0].applies_to must be a non-empty string",
    );
  });

  it("rejects a pattern with a blank example_path when set (line 50/51)", () => {
    const result = normalizeVocab({
      patterns: [{ name: "P", applies_to: "src/**", example_path: "" }],
    });
    assertError(
      result,
      "architecture.vocabulary.patterns[0].example_path must be a non-empty string when set",
    );
  });
});

describe("normalizeArchitectureConfig vocabulary.canonical_helpers validation", () => {
  it("rejects a non-list canonical_helpers value (line 64/65)", () => {
    const result = normalizeVocab({ canonical_helpers: 7 });
    assertError(
      result,
      "architecture.vocabulary.canonical_helpers must be a list when set",
    );
  });

  it("rejects a helper entry that is not a mapping (line 71/72/73)", () => {
    const result = normalizeVocab({ canonical_helpers: [["nested"]] });
    assertError(result, "architecture.vocabulary.canonical_helpers[0] must be a mapping");
  });

  it("rejects a helper with an unknown key (line 76/77)", () => {
    const result = normalizeVocab({
      canonical_helpers: [{ name: "H", purpose: "does x", bogus: 1 }],
    });
    assertError(
      result,
      "architecture.vocabulary.canonical_helpers[0] has unknown key 'bogus'",
    );
  });

  it("rejects a helper with a blank name (line 80/81)", () => {
    const result = normalizeVocab({
      canonical_helpers: [{ name: "", purpose: "does x" }],
    });
    assertError(
      result,
      "architecture.vocabulary.canonical_helpers[0].name must be a non-empty string",
    );
  });

  it("rejects a helper with a blank path when set (line 86/87)", () => {
    const result = normalizeVocab({
      canonical_helpers: [{ name: "H", purpose: "does x", path: "   " }],
    });
    assertError(
      result,
      "architecture.vocabulary.canonical_helpers[0].path must be a non-empty string when set",
    );
  });
});

describe("normalizeArchitectureConfig vocabulary.boundary_contract validation", () => {
  it("rejects a boundary_contract that is not a mapping (line 100/101)", () => {
    const result = normalizeVocab({ boundary_contract: "text" });
    assertError(
      result,
      "architecture.vocabulary.boundary_contract must be a mapping when set",
    );
  });

  it("rejects a boundary_contract with an unknown key (line 105/106)", () => {
    const result = normalizeVocab({
      boundary_contract: { description: "no cross-layer imports", bogus: true },
    });
    assertError(
      result,
      "architecture.vocabulary.boundary_contract has unknown key 'bogus'",
    );
  });
});

describe("normalizeArchitectureConfig vocabulary.binding_adrs validation", () => {
  it("rejects a non-list binding_adrs value (line 117/118/119)", () => {
    const result = normalizeVocab({ binding_adrs: "ADR-001" });
    assertError(result, "architecture.vocabulary.binding_adrs must be a list when set");
  });

  it("rejects a binding_adr entry that is not a mapping (line 124/125/126)", () => {
    const result = normalizeVocab({ binding_adrs: [null] });
    assertError(result, "architecture.vocabulary.binding_adrs[0] must be a mapping");
  });

  it("rejects a binding_adr with an unknown key (line 129)", () => {
    const result = normalizeVocab({
      binding_adrs: [{ id: "ADR-001", one_liner: "use X", bogus: 1 }],
    });
    assertError(
      result,
      "architecture.vocabulary.binding_adrs[0] has unknown key 'bogus'",
    );
  });

  it("rejects a binding_adr with a blank one_liner (line 136/137)", () => {
    const result = normalizeVocab({ binding_adrs: [{ id: "ADR-002", one_liner: "" }] });
    assertError(
      result,
      "architecture.vocabulary.binding_adrs[0].one_liner must be a non-empty string",
    );
  });
});

describe("normalizeArchitectureConfig vocabulary.anti_recommendations validation", () => {
  it("rejects a non-list anti_recommendations value (line 146/147/148)", () => {
    const result = normalizeVocab({ anti_recommendations: "avoid X" });
    assertError(
      result,
      "architecture.vocabulary.anti_recommendations must be a list when set",
    );
  });
});

describe("normalizeArchitectureConfig vocabulary shape validation", () => {
  it("rejects a vocabulary that is a list (line 163/164)", () => {
    const result = normalizeVocab([1, 2, 3]);
    assertError(
      result,
      "architecture.vocabulary must be a mapping, not a list or scalar",
    );
  });

  it("rejects a vocabulary that is a scalar (line 163/164)", () => {
    const result = normalizeVocab(42);
    assertError(
      result,
      "architecture.vocabulary must be a mapping, not a list or scalar",
    );
  });
});

describe("normalizeArchitectureConfig valid vocabulary normalization", () => {
  it("accepts a fully valid vocabulary and returns the normalized value", () => {
    const result = normalizeArchitectureConfig({
      vocabulary: {
        patterns: [{ name: "P", applies_to: "src/**", example_path: "src/a.js" }],
        canonical_helpers: [{ name: "H", purpose: "does x", path: "lib/h.js" }],
        boundary_contract: { description: "no cross-layer imports" },
        binding_adrs: [{ id: "ADR-001", one_liner: "use X" }],
        anti_recommendations: ["do not do Y"],
      },
    });
    assert.deepEqual(result, {
      ok: true,
      value: {
        vocabulary: {
          patterns: [{ name: "P", applies_to: "src/**", example_path: "src/a.js" }],
          canonical_helpers: [{ name: "H", purpose: "does x", path: "lib/h.js" }],
          boundary_contract: { description: "no cross-layer imports" },
          binding_adrs: [{ id: "ADR-001", one_liner: "use X" }],
          anti_recommendations: ["do not do Y"],
        },
      },
    });
  });

  it("defaults optional pattern/helper fields to null when omitted", () => {
    const result = normalizeArchitectureConfig({
      vocabulary: {
        patterns: [{ name: "P", applies_to: "src/**" }],
        canonical_helpers: [{ name: "H", purpose: "does x" }],
      },
    });
    assert.equal(result.ok, true);
    assert.equal(result.value.vocabulary.patterns[0].example_path, null);
    assert.equal(result.value.vocabulary.canonical_helpers[0].path, null);
    assert.equal(result.value.vocabulary.boundary_contract, null);
  });

  it("treats a null architecture as valid (no config)", () => {
    assert.deepEqual(normalizeArchitectureConfig(null), { ok: true, value: null });
  });

  it("treats an absent vocabulary as a null vocabulary value", () => {
    assert.deepEqual(normalizeArchitectureConfig({}), {
      ok: true,
      value: { vocabulary: null },
    });
  });
});

// Adjacent normalizer branches not in the primary uncovered set, exercised so
// this file covers the architecture-vocabulary normalizer region on its own.
describe("normalizeArchitectureConfig remaining normalizer branches", () => {
  it("rejects an architecture that is not a mapping", () => {
    assertError(
      normalizeArchitectureConfig([1, 2]),
      "architecture must be a mapping, not a list or scalar",
    );
  });

  it("rejects an unknown top-level architecture key", () => {
    assertError(
      normalizeArchitectureConfig({ bogus: 1 }),
      "architecture has unknown key 'bogus'",
    );
  });

  it("rejects an unknown vocabulary key", () => {
    assertError(normalizeVocab({ bogus: 1 }), "architecture.vocabulary has unknown key 'bogus'");
  });

  it("rejects a pattern with an unknown key and a blank name", () => {
    const result = normalizeVocab({ patterns: [{ name: "", applies_to: "src/**", bogus: 1 }] });
    assertError(result, "architecture.vocabulary.patterns[0] has unknown key 'bogus'");
    assertError(result, "architecture.vocabulary.patterns[0].name must be a non-empty string");
  });

  it("rejects a helper with a blank purpose", () => {
    assertError(
      normalizeVocab({ canonical_helpers: [{ name: "H", purpose: "" }] }),
      "architecture.vocabulary.canonical_helpers[0].purpose must be a non-empty string",
    );
  });

  it("rejects a boundary_contract with a blank description", () => {
    assertError(
      normalizeVocab({ boundary_contract: { description: "" } }),
      "architecture.vocabulary.boundary_contract.description must be a non-empty string",
    );
  });

  it("rejects a binding_adr whose id does not match the ADR pattern", () => {
    assertError(
      normalizeVocab({ binding_adrs: [{ id: "XYZ", one_liner: "use X" }] }),
      "architecture.vocabulary.binding_adrs[0].id must match ^ADR-\\d{3}$",
    );
  });

  it("rejects a blank anti_recommendations element", () => {
    assertError(
      normalizeVocab({ anti_recommendations: [""] }),
      "architecture.vocabulary.anti_recommendations[0] must be a non-empty string",
    );
  });
});
