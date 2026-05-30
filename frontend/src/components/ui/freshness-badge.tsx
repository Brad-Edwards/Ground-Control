import type { FreshnessState } from "@/types/api";

/**
 * Shared evidence-freshness badge. Used across the GRC workspaces (control,
 * evidence, portfolio) so freshness is rendered consistently rather than each
 * page re-deriving its own style/label map. Mirrors the EvidenceFreshnessAnalysisService
 * dominant-state vocabulary (GC-L007).
 */
const STYLE: Record<FreshnessState, string> = {
  FRESH: "bg-green-100 text-green-800",
  STALE: "bg-yellow-100 text-yellow-800",
  EXPIRED: "bg-red-100 text-red-800",
  SUPERSEDED: "bg-blue-100 text-blue-800",
  NO_OBSERVATIONS: "bg-slate-100 text-slate-600",
};

const LABEL: Record<FreshnessState, string> = {
  FRESH: "Fresh",
  STALE: "Stale",
  EXPIRED: "Expired",
  SUPERSEDED: "Superseded",
  NO_OBSERVATIONS: "No observations",
};

export function FreshnessBadge({ state }: { state: FreshnessState }) {
  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${STYLE[state]}`}
      aria-label={`Evidence freshness: ${state}`}
    >
      {LABEL[state]}
    </span>
  );
}
