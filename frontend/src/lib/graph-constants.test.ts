import { GRAPH_ENTITY_TYPES } from "@/types/api";
import { describe, expect, it } from "vitest";
import {
  ENTITY_TYPE_COLORS,
  PRIORITY_COLORS,
  SERIES_COLORS,
  STATUS_COLORS,
  WAVE_COLORS,
  getColorMap,
  getEntityTypeColor,
  getNodeColor,
  getSeries,
} from "./graph-constants";

describe("getSeries", () => {
  it("extracts series letter from a valid UID", () => {
    expect(getSeries("GC-A001")).toBe("A");
    expect(getSeries("GC-Q005")).toBe("Q");
    expect(getSeries("GC-Z999")).toBe("Z");
  });

  it("returns '?' for non-matching UIDs", () => {
    expect(getSeries("bad")).toBe("?");
    expect(getSeries("")).toBe("?");
    expect(getSeries("REQ-001")).toBe("?");
  });
});

describe("getNodeColor", () => {
  const req = { uid: "GC-A001", priority: "MUST", status: "ACTIVE", wave: 2 };

  it("returns series color by default", () => {
    expect(getNodeColor(req, "series")).toBe(SERIES_COLORS.A);
  });

  it("returns priority color", () => {
    expect(getNodeColor(req, "priority")).toBe(PRIORITY_COLORS.MUST);
  });

  it("returns status color", () => {
    expect(getNodeColor(req, "status")).toBe(STATUS_COLORS.ACTIVE);
  });

  it("returns wave color", () => {
    expect(getNodeColor(req, "wave")).toBe(WAVE_COLORS["2"]);
  });

  it("returns fallback for unknown values", () => {
    const unknown = {
      entityType: "REQUIREMENT",
      uid: "GC-A001",
      priority: "NOPE",
      status: "ACTIVE",
      wave: 0,
    };
    expect(getNodeColor(unknown, "priority")).toBe("#6c7ee1");
  });
});

describe("ENTITY_TYPE_COLORS", () => {
  it("includes EVIDENCE_ARTIFACT (GC-M016 / ADR-044)", () => {
    // Locks in the frontend mirror for the backend GraphEntityType.EVIDENCE_ARTIFACT
    // node so the graph visualization does not fall through to the generic gray
    // entity color.
    expect(ENTITY_TYPE_COLORS.EVIDENCE_ARTIFACT).toBeDefined();
    expect(getEntityTypeColor("EVIDENCE_ARTIFACT")).toBe(
      ENTITY_TYPE_COLORS.EVIDENCE_ARTIFACT,
    );
  });

  // GC-G007: every backend GraphEntityType value must have an entry in
  // ENTITY_TYPE_COLORS so the graph visualization never silently falls
  // through to the generic grey fallback for a shipped entity type.
  it.each(GRAPH_ENTITY_TYPES)("has a color entry for %s", (entityType) => {
    expect(ENTITY_TYPE_COLORS[entityType]).toBeDefined();
    expect(typeof ENTITY_TYPE_COLORS[entityType]).toBe("string");
  });

  it("gives workflow reporting nodes distinct registered colors", () => {
    expect(ENTITY_TYPE_COLORS.WORKFLOW_RUN).toBeDefined();
    expect(ENTITY_TYPE_COLORS.WORK_ITEM_REFERENCE).toBeDefined();
    expect(ENTITY_TYPE_COLORS.WORKFLOW_RUN).not.toBe(
      ENTITY_TYPE_COLORS.WORK_ITEM_REFERENCE,
    );
  });
});

describe("getColorMap", () => {
  it("returns priority colors for priority scheme", () => {
    expect(getColorMap("priority")).toBe(PRIORITY_COLORS);
  });

  it("returns status colors for status scheme", () => {
    expect(getColorMap("status")).toBe(STATUS_COLORS);
  });

  it("returns series colors for series scheme", () => {
    expect(getColorMap("series")).toBe(SERIES_COLORS);
  });

  it("returns wave-prefixed keys for wave scheme", () => {
    const map = getColorMap("wave");
    expect(map["Wave 1"]).toBe(WAVE_COLORS["1"]);
    expect(map["Wave 5"]).toBe(WAVE_COLORS["5"]);
    expect(Object.keys(map).every((k) => k.startsWith("Wave "))).toBe(true);
  });
});
