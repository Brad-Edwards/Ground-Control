import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const __filename = fileURLToPath(import.meta.url);
export const __dirname = path.dirname(__filename);
export const repoRoot = path.resolve(__dirname, "..", "..");

export async function readJson(relativePath) {
  const fullPath = path.resolve(repoRoot, relativePath);
  return JSON.parse(await readFile(fullPath, "utf8"));
}

export async function readText(relativePath) {
  const fullPath = path.resolve(repoRoot, relativePath);
  return readFile(fullPath, "utf8");
}

export async function findAdrPathByUid(uid) {
  const adrNumber = uid.split("-")[1];
  const { glob } = await import("node:fs/promises");
  const pattern = path.join(repoRoot, "architecture", "adrs", `${adrNumber}-*.md`);
  const matches = [];
  for await (const match of glob(pattern)) {
    matches.push(match);
  }
  if (matches.length !== 1) {
    throw new Error(`Expected exactly one ADR file for ${uid}, found ${matches.length}`);
  }
  return matches[0];
}

export function compareGateShape(liveGate, desiredGate) {
  return (
    readGateField(liveGate, "metricType", "metric_type") === desiredGate.metricType
    && (readGateField(liveGate, "metricParam", "metric_param") ?? null) === (desiredGate.metricParam ?? null)
    && (readGateField(liveGate, "scopeStatus", "scope_status") ?? null) === (desiredGate.scopeStatus ?? null)
    && readGateField(liveGate, "operator") === desiredGate.operator
    && readGateField(liveGate, "description") === desiredGate.description
    && readGateField(liveGate, "enabled") === true
  );
}

export function summarizeSweep(report) {
  const coverageGaps = {};
  for (const [linkType, gaps] of Object.entries(report.coverageGaps ?? {})) {
    coverageGaps[linkType] = gaps.length;
  }
  return {
    cycles: report.cycles.length,
    orphans: report.orphans.length,
    crossWaveViolations: report.crossWaveViolations.length,
    consistencyViolations: report.consistencyViolations.length,
    completenessIssues: report.completeness?.issues?.length ?? 0,
    qualityGateFailures: report.qualityGateResults?.failedCount ?? 0,
    coverageGaps,
  };
}

export function parseRepoAdr(markdown) {
  const lines = markdown.split(/\r?\n/);
  let title = null;
  let status = null;
  let date = null;

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (!title && line.startsWith("# ")) {
      title = line.replace(/^#\s+/, "").trim();
      continue;
    }
    if (line === "## Status") {
      status = nextNonEmpty(lines, i + 1);
      continue;
    }
    if (line === "## Date") {
      date = nextNonEmpty(lines, i + 1);
    }
  }

  if (!title || !status || !date) {
    throw new Error("Unable to parse ADR title/status/date.");
  }

  const uid = title.split(":")[0].trim();
  return { uid, title: title.replace(/^[^:]+:\s*/, ""), status: status.toUpperCase(), decisionDate: date };
}

function nextNonEmpty(lines, startIndex) {
  for (let i = startIndex; i < lines.length; i += 1) {
    const candidate = lines[i].trim();
    if (candidate) {
      return candidate;
    }
  }
  return null;
}

export function normalizeAdrStatus(status) {
  return status.trim().toUpperCase().replace(/\s+/g, "_");
}

export function readGateField(gate, ...keys) {
  for (const key of keys) {
    if (gate[key] !== undefined) {
      return gate[key];
    }
  }
  return undefined;
}

export function gateName(gate) {
  return readGateField(gate, "name", "gateName", "gate_name");
}

export function gateActualValue(gate) {
  return readGateField(gate, "actualValue", "actual_value");
}

export function adrDecisionDate(adr) {
  return adr.decisionDate ?? adr.decision_date;
}

export function adrTitle(adr) {
  return adr.title ?? adr.folderTitle ?? adr.folder_title;
}

export function adrStatus(adr) {
  return adr.status;
}
