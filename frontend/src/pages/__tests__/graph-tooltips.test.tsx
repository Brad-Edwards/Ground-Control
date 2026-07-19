/**
 * GC-G008 — Mixed-Entity Graph Traversal and Visualization
 *
 * Tests that getTooltipTags returns the correct tags for every GraphEntityType
 * member so that the graph tooltip has full entity coverage.
 */
import { describe, expect, it } from "vitest";
import { GRAPH_ENTITY_TYPES } from "@/types/api";
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

// Shared status enum value reused across multiple per-type fixtures and their
// expected-tag assertions (S1192 — avoid duplicating this literal).
const STATUS_ACTIVE = "ACTIVE";

describe("getTooltipTags — does not throw for any GraphEntityType", () => {
  it.each(GRAPH_ENTITY_TYPES)(
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

describe("getTooltipTags — ARTIFACT_REFERENCE node", () => {
  it("returns artifact type and exact identifier tags", () => {
    expectExactTags(
      {
        entityType: "ARTIFACT_REFERENCE",
        artifactType: "CODE_FILE",
        artifactIdentifier: "src/main/java/Exact Case.java",
      },
      ["Type: CODE_FILE", "Identifier: src/main/java/Exact Case.java"],
    );
  });
});

describe("getTooltipTags — research projection nodes (ADR-070)", () => {
  it("returns status, stage, and autonomy tags for a RESEARCH_RUN node", () => {
    expectTagsIncluding(
      {
        entityType: "RESEARCH_RUN",
        status: "IN_PROGRESS",
        currentStage: "CHARTING",
        autonomyLevel: "AUTONOMOUS",
      },
      ["IN_PROGRESS", "CHARTING", "AUTONOMOUS"],
    );
  });

  it("returns type, stage, and status tags for a RESEARCH_ARTIFACT node", () => {
    expectTagsIncluding(
      {
        entityType: "RESEARCH_ARTIFACT",
        artifactType: "PROTOCOL_PLAN",
        stage: "METHODOLOGY_SELECTION",
        status: STATUS_ACTIVE,
      },
      ["PROTOCOL_PLAN", "METHODOLOGY_SELECTION", STATUS_ACTIVE],
    );
  });

  it("returns kind, status, and external id tags for a RESEARCH_PROVENANCE_NODE node", () => {
    expectTagsIncluding(
      {
        entityType: "RESEARCH_PROVENANCE_NODE",
        kind: "CANDIDATE_SOURCE",
        status: STATUS_ACTIVE,
        externalIdentifier: "doi:10.1/x",
      },
      ["CANDIDATE_SOURCE", STATUS_ACTIVE, "doi:10.1/x"],
    );
  });
});

describe("getTooltipTags — workflow reporting projection nodes", () => {
  it("returns workflow type, state, and outcome for a WORKFLOW_RUN node", () => {
    expectExactTags(
      {
        entityType: "WORKFLOW_RUN",
        workflowType: "IMPLEMENT",
        finalState: "READY_FOR_REVIEW",
        outcome: "NONE",
      },
      ["Workflow: IMPLEMENT", "State: READY_FOR_REVIEW", "Outcome: NONE"],
    );
  });

  it("returns repository and issue number for a WORK_ITEM_REFERENCE node", () => {
    expectExactTags(
      {
        entityType: "WORK_ITEM_REFERENCE",
        repo: "autarchy-ai/Ground-Control",
        issueNumber: 1311,
      },
      ["Repository: autarchy-ai/Ground-Control", "Issue: 1311"],
    );
  });
});
