export const SERIES_COLORS: Record<string, string> = {
  A: "#6c7ee1",
  B: "#4ecdc4",
  C: "#f7b731",
  D: "#e056a0",
  E: "#45b7d1",
  F: "#96c93d",
  G: "#ff6b6b",
  H: "#c56cf0",
  I: "#786fa6",
  J: "#f19066",
  K: "#e77f67",
  L: "#574b90",
  M: "#e15f41",
  N: "#3dc1d3",
  O: "#ea8685",
  P: "#778beb",
  Q: "#cf6a87",
  R: "#63cdda",
  S: "#f8a5c2",
  T: "#f3a683",
  U: "#c44569",
  V: "#546de5",
  W: "#e66767",
  X: "#303952",
};

export const ENTITY_TYPE_COLORS: Record<string, string> = {
  REQUIREMENT: "#6c7ee1",
  OPERATIONAL_ASSET: "#22c55e",
  OBSERVATION: "#0ea5e9",
  RISK_SCENARIO: "#f97316",
  EVIDENCE_ARTIFACT: "#38bdf8",
  CONTROL: "#16a34a",
  CONTROL_TEST: "#84cc16",
  VERIFICATION_RESULT: "#06b6d4",
  THREAT_MODEL: "#dc2626",
  FINDING: "#ec4899",
  AUDIT: "#8b5cf6",
  RISK_CONTROL_MAPPING: "#f59e0b",
  SCOPED_CONTROL_IMPLEMENTATION: "#0891b2",
  DOCUMENT: "#64748b",
  RESEARCH_RUN: "#4f46e5",
  RESEARCH_ARTIFACT: "#0d9488",
  RESEARCH_PROVENANCE_NODE: "#7e22ce",
  ARTIFACT_REFERENCE: "#475569",
};

export const PRIORITY_COLORS: Record<string, string> = {
  MUST: "#e74c3c",
  SHOULD: "#f39c12",
  COULD: "#3498db",
  WONT: "#7f8c8d",
};

export const STATUS_COLORS: Record<string, string> = {
  DRAFT: "#7f8c8d",
  ACTIVE: "#2ecc71",
  DEPRECATED: "#e67e22",
  ARCHIVED: "#95a5a6",
};

export const WAVE_COLORS: Record<string, string> = {
  1: "#e74c3c",
  2: "#e67e22",
  3: "#f1c40f",
  4: "#2ecc71",
  5: "#3498db",
  6: "#9b59b6",
  7: "#1abc9c",
  8: "#e91e63",
  9: "#00bcd4",
};

export const RELATION_STYLES: Record<
  string,
  { color: string; style: string; label: string }
> = {
  DEPENDS_ON: { color: "#6c7ee1", style: "solid", label: "depends" },
  PARENT: { color: "#4ecdc4", style: "solid", label: "parent" },
  REFINES: { color: "#f7b731", style: "dashed", label: "refines" },
  CONFLICTS_WITH: { color: "#e74c3c", style: "dotted", label: "conflicts" },
  SUPERSEDES: { color: "#c56cf0", style: "dashed", label: "supersedes" },
  OBSERVED_ON: { color: "#0ea5e9", style: "dashed", label: "observed on" },
  TRACKS: { color: "#ef4444", style: "solid", label: "tracks" },
  ASSESSES: { color: "#a855f7", style: "solid", label: "assesses" },
  USES_METHOD: { color: "#14b8a6", style: "dashed", label: "uses method" },
  USED_OBSERVATION: {
    color: "#38bdf8",
    style: "dotted",
    label: "used observation",
  },
  TREATS: { color: "#eab308", style: "solid", label: "treats" },
  IMPLEMENTS: { color: "#16a34a", style: "solid", label: "implements" },
  MITIGATES: { color: "#f97316", style: "solid", label: "mitigates" },
  SUBJECT_OF: { color: "#64748b", style: "dashed", label: "subject of" },
  EVIDENCED_BY: { color: "#38bdf8", style: "dotted", label: "evidenced by" },
  GOVERNED_BY: { color: "#a855f7", style: "dashed", label: "governed by" },
  ASSOCIATED: { color: "#94a3b8", style: "dotted", label: "associated" },
  MITIGATED_BY: { color: "#f97316", style: "solid", label: "mitigated by" },
  DOCUMENTED_IN: {
    color: "#14b8a6",
    style: "dashed",
    label: "documented in",
  },
  RELATED: { color: "#95a5a6", style: "dotted", label: "related" },
};

export type ColorScheme = "entity" | "series" | "priority" | "status" | "wave";
export type LayoutId =
  | "dagre-lr"
  | "dagre-tb"
  | "dagre-wave-lr"
  | "dagre-wave-tb";

export function getSeries(uid: string): string {
  const m = uid.match(/^GC-([A-Z])/);
  return m?.[1] ?? "?";
}

export function getEntityTypeColor(entityType?: string | null): string {
  return ENTITY_TYPE_COLORS[entityType ?? ""] ?? "#64748b";
}

export function getNodeColor(
  req: {
    entityType?: string | null;
    uid?: string | null;
    priority?: string | null;
    status?: string | null;
    wave?: number | null;
  },
  scheme: ColorScheme,
): string {
  const fallback = getEntityTypeColor(req.entityType);
  const series = getSeries(req.uid ?? "");
  switch (scheme) {
    case "entity":
      return fallback;
    case "priority":
      return PRIORITY_COLORS[req.priority ?? ""] ?? fallback;
    case "status":
      return STATUS_COLORS[req.status ?? ""] ?? fallback;
    case "wave":
      return WAVE_COLORS[String(req.wave ?? "")] ?? fallback;
    default:
      return SERIES_COLORS[series] ?? fallback;
  }
}

export function getColorMap(scheme: ColorScheme): Record<string, string> {
  switch (scheme) {
    case "entity":
      return ENTITY_TYPE_COLORS;
    case "priority":
      return PRIORITY_COLORS;
    case "status":
      return STATUS_COLORS;
    case "wave": {
      const map: Record<string, string> = {};
      for (const [k, v] of Object.entries(WAVE_COLORS)) {
        map[`Wave ${k}`] = v;
      }
      return map;
    }
    default:
      return SERIES_COLORS;
  }
}
