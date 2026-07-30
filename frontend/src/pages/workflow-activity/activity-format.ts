import type { BadgeVariant } from "@/components/ui/badge";
import type { WorkflowActivityResponse } from "@/types/api";
import { useEffect, useState } from "react";

export type OpenActivityRun = WorkflowActivityResponse["openRuns"][number];
export type GateAttempt = OpenActivityRun["gates"][number];

export function useSnapshotClock(observedAt: string | undefined): number {
  const [now, setNow] = useState(() =>
    observedAt ? Date.parse(observedAt) : Date.now(),
  );

  useEffect(() => {
    if (!observedAt) return;
    const serverAt = Date.parse(observedAt);
    const receivedAt = Date.now();
    setNow(serverAt);
    const timer = window.setInterval(() => {
      setNow(serverAt + Math.max(0, Date.now() - receivedAt));
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [observedAt]);

  return now;
}

export function elapsedMs(
  now: number,
  since: string | null | undefined,
): number | null {
  if (!since) return null;
  return Math.max(0, now - Date.parse(since));
}

export function formatElapsed(ms: number | null): string {
  if (ms == null || !Number.isFinite(ms)) return "Unobserved";
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return "<1m";
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  if (hours < 24) return remainder ? `${hours}h ${remainder}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const remainingHours = hours % 24;
  return remainingHours ? `${days}d ${remainingHours}h` : `${days}d`;
}

export function attentionLabel(
  row: OpenActivityRun,
  now: number,
): string | null {
  const since = row.currentPhaseSince ?? row.run.startedAt;
  const elapsed = elapsedMs(now, since);
  if (elapsed == null || elapsed <= row.stallThresholdMs) return null;
  const duration = formatElapsed(elapsed);
  if (row.run.finalState === "READY_FOR_REVIEW") {
    return `Waiting beyond threshold — ${duration} without a lifecycle transition`;
  }
  if (row.run.finalState === "ESCALATED") {
    return `Escalated beyond threshold — ${duration} without a lifecycle transition`;
  }
  return `Possibly stalled — ${duration} without a lifecycle transition`;
}

export function gateVariant(gate: GateAttempt): BadgeVariant {
  if (gate.eventType === "STARTED") return "info";
  if (gate.stationResult === "PASS") return "success";
  if (gate.stationResult === "FAIL" || gate.eventType === "FAILED") {
    return "danger";
  }
  if (gate.eventType === "ESCALATED") return "warning";
  return "neutral";
}

export function gateLabel(gate: GateAttempt): string {
  if (gate.eventType === "STARTED") return "Running";
  if (gate.eventType == null || gate.stationResult === "UNOBSERVED") {
    return "Unobserved";
  }
  if (gate.stationResult === "PASS") return "Passed";
  if (gate.stationResult === "FAIL" || gate.eventType === "FAILED") {
    return "Failed";
  }
  if (gate.eventType === "ESCALATED") return "Escalated";
  if (
    gate.eventType === "SKIPPED" ||
    gate.stationResult === "SKIPPED_STATION"
  ) {
    return "Skipped";
  }
  if (gate.stationResult === "CANCELLED") return "Cancelled";
  if (gate.stationResult === "NOT_EVALUABLE") return "Not evaluable";
  return "Observed";
}
