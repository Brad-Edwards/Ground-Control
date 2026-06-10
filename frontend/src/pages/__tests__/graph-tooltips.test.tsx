/**
 * GC-G008 — Mixed-Entity Graph Traversal and Visualization
 *
 * Tests that getTooltipTags returns the correct tags for every GraphEntityType
 * member so that the graph tooltip has full entity coverage.
 */
import { describe, expect, it } from "vitest";
import { getTooltipTags } from "../graph";

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
    const data = {
      entityType: "REQUIREMENT",
      priority: "MUST",
      status: "ACTIVE",
      wave: 4,
      type: "FUNCTIONAL",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts).toContain("MUST");
    expect(texts).toContain("ACTIVE");
    expect(texts).toContain("Wave 4");
    expect(texts).toContain("FUNCTIONAL");
  });
});

describe("getTooltipTags — OPERATIONAL_ASSET node", () => {
  it("returns assetType, name, and knowledgeState tags", () => {
    const data = {
      entityType: "OPERATIONAL_ASSET",
      assetType: "SERVICE",
      assetName: "payments-api",
      knowledgeState: "KNOWN",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Asset Type: SERVICE")).toBe(true);
    expect(texts.some((t) => t === "Name: payments-api")).toBe(true);
    expect(texts.some((t) => t === "Knowledge: KNOWN")).toBe(true);
  });
});

describe("getTooltipTags — OBSERVATION node", () => {
  it("returns category, source, and confidence tags", () => {
    const data = {
      entityType: "OBSERVATION",
      category: "MEASUREMENT",
      source: "monitoring",
      confidence: "HIGH",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Category: MEASUREMENT")).toBe(true);
    expect(texts.some((t) => t === "Source: monitoring")).toBe(true);
    expect(texts.some((t) => t === "Confidence: HIGH")).toBe(true);
  });
});

describe("getTooltipTags — RISK_SCENARIO node", () => {
  it("returns status, threat, and method tags", () => {
    const data = {
      entityType: "RISK_SCENARIO",
      status: "ACTIVE",
      threat: "external-attacker",
      method: "credential-theft",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Status: ACTIVE")).toBe(true);
    expect(texts.some((t) => t === "Threat: external-attacker")).toBe(true);
    expect(texts.some((t) => t === "Method: credential-theft")).toBe(true);
  });
});

describe("getTooltipTags — RISK_REGISTER_RECORD node", () => {
  it("returns status, owner, and cadence tags", () => {
    const data = {
      entityType: "RISK_REGISTER_RECORD",
      status: "OPEN",
      owner: "risk-team",
      reviewCadence: "QUARTERLY",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Status: OPEN")).toBe(true);
    expect(texts.some((t) => t === "Owner: risk-team")).toBe(true);
    expect(texts.some((t) => t === "Cadence: QUARTERLY")).toBe(true);
  });
});

describe("getTooltipTags — RISK_ASSESSMENT_RESULT node", () => {
  it("returns approval, confidence, and analyst tags", () => {
    const data = {
      entityType: "RISK_ASSESSMENT_RESULT",
      approvalState: "APPROVED",
      confidence: "HIGH",
      analystIdentity: "alice",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Approval: APPROVED")).toBe(true);
    expect(texts.some((t) => t === "Confidence: HIGH")).toBe(true);
    expect(texts.some((t) => t === "Analyst: alice")).toBe(true);
  });
});

describe("getTooltipTags — TREATMENT_PLAN node", () => {
  it("returns strategy, status, and owner tags", () => {
    const data = {
      entityType: "TREATMENT_PLAN",
      strategy: "MITIGATE",
      status: "IN_PROGRESS",
      owner: "platform-team",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Strategy: MITIGATE")).toBe(true);
    expect(texts.some((t) => t === "Status: IN_PROGRESS")).toBe(true);
    expect(texts.some((t) => t === "Owner: platform-team")).toBe(true);
  });
});

describe("getTooltipTags — METHODOLOGY_PROFILE node", () => {
  it("returns family, version, and status tags", () => {
    const data = {
      entityType: "METHODOLOGY_PROFILE",
      family: "NIST",
      version: "1.2.3",
      status: "ACTIVE",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Family: NIST")).toBe(true);
    expect(texts.some((t) => t === "Version: 1.2.3")).toBe(true);
    expect(texts.some((t) => t === "Status: ACTIVE")).toBe(true);
  });
});

describe("getTooltipTags — FINDING node with representative properties", () => {
  it("returns severity, findingType, and status tags", () => {
    const data = {
      entityType: "FINDING",
      severity: "HIGH",
      findingType: "GAP",
      status: "OPEN",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("HIGH"))).toBe(true);
    expect(texts.some((t) => t.includes("GAP"))).toBe(true);
    expect(texts.some((t) => t.includes("OPEN"))).toBe(true);
  });
});

describe("getTooltipTags — DOCUMENT node with representative properties", () => {
  it("returns version and createdBy tags", () => {
    const data = {
      entityType: "DOCUMENT",
      version: "1.0.0",
      createdBy: "alice",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("1.0.0"))).toBe(true);
    expect(texts.some((t) => t.includes("alice"))).toBe(true);
  });
});

describe("getTooltipTags — CONTROL node with representative properties", () => {
  it("returns status, owner, and category tags", () => {
    const data = {
      entityType: "CONTROL",
      status: "ACTIVE",
      owner: "security-team",
      category: "TECHNICAL",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("ACTIVE"))).toBe(true);
    expect(texts.some((t) => t.includes("security-team"))).toBe(true);
    expect(texts.some((t) => t.includes("TECHNICAL"))).toBe(true);
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
    const data = {
      entityType: "CONTROL_TEST",
      methodology: "MANUAL",
      conclusion: "PASSED",
      testerIdentity: "bob",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("MANUAL"))).toBe(true);
    expect(texts.some((t) => t.includes("PASSED"))).toBe(true);
    expect(texts.some((t) => t.includes("bob"))).toBe(true);
  });
});

describe("getTooltipTags — VERIFICATION_RESULT node", () => {
  it("returns prover, result, and assuranceLevel tags", () => {
    const data = {
      entityType: "VERIFICATION_RESULT",
      prover: "openJML",
      result: "VERIFIED",
      assuranceLevel: "HIGH",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("openJML"))).toBe(true);
    expect(texts.some((t) => t.includes("VERIFIED"))).toBe(true);
    expect(texts.some((t) => t.includes("HIGH"))).toBe(true);
  });
});

describe("getTooltipTags — EVIDENCE_ARTIFACT node", () => {
  it("returns evidenceType, assuranceLevel, and derivedBy tags", () => {
    const data = {
      entityType: "EVIDENCE_ARTIFACT",
      evidenceType: "LOG_EXPORT",
      assuranceLevel: "MEDIUM",
      derivedBy: "carol",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("LOG_EXPORT"))).toBe(true);
    expect(texts.some((t) => t.includes("MEDIUM"))).toBe(true);
    expect(texts.some((t) => t.includes("carol"))).toBe(true);
  });
});

describe("getTooltipTags — AUDIT node", () => {
  it("returns auditType, status, and createdBy tags", () => {
    const data = {
      entityType: "AUDIT",
      auditType: "INTERNAL",
      status: "IN_PROGRESS",
      createdBy: "dave",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("INTERNAL"))).toBe(true);
    expect(texts.some((t) => t.includes("IN_PROGRESS"))).toBe(true);
    expect(texts.some((t) => t.includes("dave"))).toBe(true);
  });
});

describe("getTooltipTags — THREAT_MODEL node", () => {
  it("returns status, threatSource, and stride tags", () => {
    const data = {
      entityType: "THREAT_MODEL",
      status: "ACTIVE",
      threatSource: "external-attacker",
      stride: "SPOOFING",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("ACTIVE"))).toBe(true);
    expect(texts.some((t) => t.includes("external-attacker"))).toBe(true);
    expect(texts.some((t) => t.includes("SPOOFING"))).toBe(true);
  });
});

describe("getTooltipTags — RISK_CONTROL_MAPPING node", () => {
  it("returns controlRole and mappingObjective tags", () => {
    const data = {
      entityType: "RISK_CONTROL_MAPPING",
      controlRole: "PREVENTIVE",
      mappingObjective: "Reduce likelihood",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("PREVENTIVE"))).toBe(true);
    expect(texts.some((t) => t.includes("Reduce likelihood"))).toBe(true);
  });
});

describe("getTooltipTags — SCOPED_CONTROL_IMPLEMENTATION node", () => {
  it("returns name and controlUid tags", () => {
    const data = {
      entityType: "SCOPED_CONTROL_IMPLEMENTATION",
      name: "Web App Firewall",
      controlUid: "GC-C001",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t.includes("Web App Firewall"))).toBe(true);
    expect(texts.some((t) => t.includes("GC-C001"))).toBe(true);
  });
});

describe("getTooltipTags — CONTROL_EFFECTIVENESS_ASSESSMENT node", () => {
  it("returns designEffectiveness, operatingEffectiveness, and assessor tags", () => {
    // Use non-overlapping values so each assertion is unambiguously tied to
    // its mapped field; previously both fields included the substring
    // "EFFECTIVE" so the design-effectiveness mapping could regress silently.
    const data = {
      entityType: "CONTROL_EFFECTIVENESS_ASSESSMENT",
      designEffectiveness: "FULLY_EFFECTIVE",
      operatingEffectiveness: "PARTIALLY_EFFECTIVE",
      assessor: "eve",
    };
    const tags = getTooltipTags(data);
    const texts = tags.map((t) => t.text);
    expect(texts.some((t) => t === "Design: FULLY_EFFECTIVE")).toBe(true);
    expect(texts.some((t) => t === "Operating: PARTIALLY_EFFECTIVE")).toBe(
      true,
    );
    expect(texts.some((t) => t === "Assessor: eve")).toBe(true);
  });
});
