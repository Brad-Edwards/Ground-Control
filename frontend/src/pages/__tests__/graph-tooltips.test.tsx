/**
 * GC-G008 — Mixed-Entity Graph Traversal and Visualization
 *
 * Tests that getTooltipTags returns the correct tags for every GraphEntityType
 * member so that the graph tooltip has full entity coverage.
 */
import { describe, expect, it } from "vitest";
import { getTooltipTags } from "../graph";

type TooltipData = Parameters<typeof getTooltipTags>[0];

/**
 * Resolve the rendered tag texts for a node's tooltip data. Mirrors the
 * `getTooltipTags(data).map((t) => t.text)` boilerplate every per-type case
 * repeated.
 */
function tooltipTexts(data: TooltipData): string[] {
  return getTooltipTags(data).map((t) => t.text);
}

/**
 * Assert that the tooltip for `data` contains a tag whose text exactly equals
 * each expected value (the `texts.some((t) => t === expected)` form used by the
 * exact-match per-type cases). `toContain` on the array is array membership,
 * i.e. exact string equality, so it is equivalent.
 */
function expectExactTags(data: TooltipData, expected: string[]) {
  const texts = tooltipTexts(data);
  for (const value of expected) {
    expect(texts).toContain(value);
  }
}

/**
 * Assert that the tooltip for `data` contains a tag whose text *includes* each
 * expected substring (the `texts.some((t) => t.includes(value))` form used by
 * the substring per-type cases).
 */
function expectTagsIncluding(data: TooltipData, substrings: string[]) {
  const texts = tooltipTexts(data);
  for (const value of substrings) {
    expect(texts.some((t) => t.includes(value))).toBe(true);
  }
}

// All 21 GraphEntityType values (mirrors backend GraphEntityType enum and
// frontend api.ts GraphEntityType union — ADR-034 enum contract).
const ALL_ENTITY_TYPES = [
  "REQUIREMENT",
  "OPERATIONAL_ASSET",
  "OBSERVATION",
  "RISK_SCENARIO",
  "RISK_REGISTER_RECORD",
  "RISK_ASSESSMENT_RESULT",
  "TREATMENT_PLAN",
  "METHODOLOGY_PROFILE",
  "EVIDENCE_ARTIFACT",
  "CONTROL",
  "CONTROL_LINK",
  "CONTROL_TEST",
  "CONTROL_EFFECTIVENESS_ASSESSMENT",
  "VERIFICATION_RESULT",
  "THREAT_MODEL",
  "FINDING",
  "AUDIT",
  "AUDIT_LINK",
  "RISK_CONTROL_MAPPING",
  "SCOPED_CONTROL_IMPLEMENTATION",
  "DOCUMENT",
] as const;

// Shared status enum value reused across multiple per-type fixtures and their
// expected-tag assertions (S1192 — avoid duplicating this literal).
const STATUS_ACTIVE = "ACTIVE";

describe("getTooltipTags — does not throw for any GraphEntityType", () => {
  it.each(ALL_ENTITY_TYPES)(
    "does not throw for entity type %s",
    (entityType) => {
      // The type tag is prepended by populateTooltip, not by getTooltipTags.
      // getTooltipTags returns DETAIL tags only and filters out empty values.
      // With no properties supplied, most types return []; we assert only that
      // the call returns an array without throwing. Behavioural coverage of
      // each type's field mapping lives in the per-type describe blocks below.
      const tags = getTooltipTags({ entityType });
      expect(Array.isArray(tags)).toBe(true);
    },
  );
});

describe("getTooltipTags — REQUIREMENT node", () => {
  it("returns priority, status, wave, and type tags", () => {
    expectExactTags(
      {
        entityType: "REQUIREMENT",
        priority: "MUST",
        status: STATUS_ACTIVE,
        wave: 4,
        type: "FUNCTIONAL",
      },
      ["MUST", STATUS_ACTIVE, "Wave 4", "FUNCTIONAL"],
    );
  });
});

describe("getTooltipTags — OPERATIONAL_ASSET node", () => {
  it("returns assetType, name, and knowledgeState tags", () => {
    expectExactTags(
      {
        entityType: "OPERATIONAL_ASSET",
        assetType: "SERVICE",
        assetName: "payments-api",
        knowledgeState: "KNOWN",
      },
      ["Asset Type: SERVICE", "Name: payments-api", "Knowledge: KNOWN"],
    );
  });
});

describe("getTooltipTags — OBSERVATION node", () => {
  it("returns category, source, and confidence tags", () => {
    expectExactTags(
      {
        entityType: "OBSERVATION",
        category: "MEASUREMENT",
        source: "monitoring",
        confidence: "HIGH",
      },
      ["Category: MEASUREMENT", "Source: monitoring", "Confidence: HIGH"],
    );
  });
});

describe("getTooltipTags — RISK_SCENARIO node", () => {
  it("returns status, threat, and method tags", () => {
    expectExactTags(
      {
        entityType: "RISK_SCENARIO",
        status: STATUS_ACTIVE,
        threat: "external-attacker",
        method: "credential-theft",
      },
      [
        "Status: ACTIVE",
        "Threat: external-attacker",
        "Method: credential-theft",
      ],
    );
  });
});

describe("getTooltipTags — RISK_REGISTER_RECORD node", () => {
  it("returns status, owner, and cadence tags", () => {
    expectExactTags(
      {
        entityType: "RISK_REGISTER_RECORD",
        status: "OPEN",
        owner: "risk-team",
        reviewCadence: "QUARTERLY",
      },
      ["Status: OPEN", "Owner: risk-team", "Cadence: QUARTERLY"],
    );
  });
});

describe("getTooltipTags — RISK_ASSESSMENT_RESULT node", () => {
  it("returns approval, confidence, and analyst tags", () => {
    expectExactTags(
      {
        entityType: "RISK_ASSESSMENT_RESULT",
        approvalState: "APPROVED",
        confidence: "HIGH",
        analystIdentity: "alice",
      },
      ["Approval: APPROVED", "Confidence: HIGH", "Analyst: alice"],
    );
  });
});

describe("getTooltipTags — TREATMENT_PLAN node", () => {
  it("returns strategy, status, and owner tags", () => {
    expectExactTags(
      {
        entityType: "TREATMENT_PLAN",
        strategy: "MITIGATE",
        status: "IN_PROGRESS",
        owner: "platform-team",
      },
      ["Strategy: MITIGATE", "Status: IN_PROGRESS", "Owner: platform-team"],
    );
  });
});

describe("getTooltipTags — METHODOLOGY_PROFILE node", () => {
  it("returns family, version, and status tags", () => {
    expectExactTags(
      {
        entityType: "METHODOLOGY_PROFILE",
        family: "NIST",
        version: "1.2.3",
        status: STATUS_ACTIVE,
      },
      ["Family: NIST", "Version: 1.2.3", "Status: ACTIVE"],
    );
  });
});

describe("getTooltipTags — FINDING node with representative properties", () => {
  it("returns severity, findingType, and status tags", () => {
    expectTagsIncluding(
      {
        entityType: "FINDING",
        severity: "HIGH",
        findingType: "GAP",
        status: "OPEN",
      },
      ["HIGH", "GAP", "OPEN"],
    );
  });
});

describe("getTooltipTags — DOCUMENT node with representative properties", () => {
  it("returns version and createdBy tags", () => {
    expectTagsIncluding(
      {
        entityType: "DOCUMENT",
        version: "1.0.0",
        createdBy: "alice",
      },
      ["1.0.0", "alice"],
    );
  });
});

describe("getTooltipTags — CONTROL node with representative properties", () => {
  it("returns status, owner, and category tags", () => {
    expectTagsIncluding(
      {
        entityType: "CONTROL",
        status: STATUS_ACTIVE,
        owner: "security-team",
        category: "TECHNICAL",
      },
      [STATUS_ACTIVE, "security-team", "TECHNICAL"],
    );
  });
});

describe("getTooltipTags — unknown/bogus entity type", () => {
  it("returns an empty array for an unknown entityType", () => {
    const tags = getTooltipTags({ entityType: "BOGUS" });
    // No detail rows for an unrecognised type; caller adds the type tag
    expect(tags).toEqual([]);
  });
});

describe("getTooltipTags — CONTROL_TEST node", () => {
  it("returns methodology, conclusion, and testerIdentity tags", () => {
    expectTagsIncluding(
      {
        entityType: "CONTROL_TEST",
        methodology: "MANUAL",
        conclusion: "PASSED",
        testerIdentity: "bob",
      },
      ["MANUAL", "PASSED", "bob"],
    );
  });
});

describe("getTooltipTags — VERIFICATION_RESULT node", () => {
  it("returns prover, result, and assuranceLevel tags", () => {
    expectTagsIncluding(
      {
        entityType: "VERIFICATION_RESULT",
        prover: "openJML",
        result: "VERIFIED",
        assuranceLevel: "HIGH",
      },
      ["openJML", "VERIFIED", "HIGH"],
    );
  });
});

describe("getTooltipTags — EVIDENCE_ARTIFACT node", () => {
  it("returns evidenceType, assuranceLevel, and derivedBy tags", () => {
    expectTagsIncluding(
      {
        entityType: "EVIDENCE_ARTIFACT",
        evidenceType: "LOG_EXPORT",
        assuranceLevel: "MEDIUM",
        derivedBy: "carol",
      },
      ["LOG_EXPORT", "MEDIUM", "carol"],
    );
  });
});

describe("getTooltipTags — AUDIT node", () => {
  it("returns auditType, status, and createdBy tags", () => {
    expectTagsIncluding(
      {
        entityType: "AUDIT",
        auditType: "INTERNAL",
        status: "IN_PROGRESS",
        createdBy: "dave",
      },
      ["INTERNAL", "IN_PROGRESS", "dave"],
    );
  });
});

describe("getTooltipTags — THREAT_MODEL node", () => {
  it("returns status, threatSource, and stride tags", () => {
    expectTagsIncluding(
      {
        entityType: "THREAT_MODEL",
        status: STATUS_ACTIVE,
        threatSource: "external-attacker",
        stride: "SPOOFING",
      },
      [STATUS_ACTIVE, "external-attacker", "SPOOFING"],
    );
  });
});

describe("getTooltipTags — RISK_CONTROL_MAPPING node", () => {
  it("returns controlRole and mappingObjective tags", () => {
    expectTagsIncluding(
      {
        entityType: "RISK_CONTROL_MAPPING",
        controlRole: "PREVENTIVE",
        mappingObjective: "Reduce likelihood",
      },
      ["PREVENTIVE", "Reduce likelihood"],
    );
  });
});

describe("getTooltipTags — SCOPED_CONTROL_IMPLEMENTATION node", () => {
  it("returns name and controlUid tags", () => {
    expectTagsIncluding(
      {
        entityType: "SCOPED_CONTROL_IMPLEMENTATION",
        name: "Web App Firewall",
        controlUid: "GC-C001",
      },
      ["Web App Firewall", "GC-C001"],
    );
  });
});

describe("getTooltipTags — CONTROL_EFFECTIVENESS_ASSESSMENT node", () => {
  it("returns designEffectiveness, operatingEffectiveness, and assessor tags", () => {
    // Use non-overlapping values so each assertion is unambiguously tied to
    // its mapped field; previously both fields included the substring
    // "EFFECTIVE" so the design-effectiveness mapping could regress silently.
    expectExactTags(
      {
        entityType: "CONTROL_EFFECTIVENESS_ASSESSMENT",
        designEffectiveness: "FULLY_EFFECTIVE",
        operatingEffectiveness: "PARTIALLY_EFFECTIVE",
        assessor: "eve",
      },
      [
        "Design: FULLY_EFFECTIVE",
        "Operating: PARTIALLY_EFFECTIVE",
        "Assessor: eve",
      ],
    );
  });
});
