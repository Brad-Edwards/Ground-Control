import path from "node:path";
import process from "node:process";

import {
  createQualityGate,
  evaluateQualityGates,
  getAdrByUid,
  listQualityGates,
  transitionAdrStatus,
  updateAdr,
  updateQualityGate,
} from "../../mcp/ground-control/lib.js";
import {
  adrDecisionDate,
  adrStatus,
  adrTitle,
  compareGateShape,
  findAdrPathByUid,
  gateActualValue,
  gateName,
  normalizeAdrStatus,
  parseRepoAdr,
  readJson,
  readText,
  readGateField,
  repoRoot,
} from "./common.mjs";

const applyMode = process.argv.includes("--apply");
const checkMode = process.argv.includes("--check") || !applyMode;

const config = await readJson("tools/ground_control/policy.json");
const project = config.project;
const existingGates = await listQualityGates(project);
const existingByName = new Map(existingGates.map((gate) => [gateName(gate), gate]));
const gateTargets = [];
const drift = [];

for (const desiredGate of config.qualityGates) {
  const threshold = desiredGate.thresholdMode === "current" ? 0 : desiredGate.threshold;
  const payload = {
    name: desiredGate.name,
    description: desiredGate.description,
    metricType: desiredGate.metricType,
    metricParam: desiredGate.metricParam ?? null,
    scopeStatus: desiredGate.scopeStatus ?? null,
    operator: desiredGate.operator,
    threshold,
    enabled: true,
  };

  const liveGate = existingByName.get(desiredGate.name);
  if (!liveGate) {
    if (checkMode) {
      drift.push(`Missing quality gate: ${desiredGate.name}`);
      continue;
    }
    const created = await createQualityGate(payload, project);
    gateTargets.push({ desiredGate, liveGate: created });
    continue;
  }

  if (!compareGateShape(liveGate, desiredGate)) {
    if (checkMode) {
      drift.push(`Quality gate drifted: ${desiredGate.name}`);
    } else {
      await updateQualityGate(readGateField(liveGate, "id"), payload);
    }
  }

  gateTargets.push({ desiredGate, liveGate: existingByName.get(desiredGate.name) ?? liveGate });
}

if (applyMode) {
  const evaluation = await evaluateQualityGates(project);
  const actualByName = new Map(evaluation.gates.map((gate) => [gateName(gate), gateActualValue(gate)]));

  for (const { desiredGate, liveGate } of gateTargets) {
    if (desiredGate.thresholdMode !== "current") {
      continue;
    }
    const actualValue = actualByName.get(desiredGate.name);
    if (actualValue === undefined) {
      throw new Error(`Unable to determine current metric value for ${desiredGate.name}`);
    }
    const currentThreshold = readGateField(liveGate, "threshold");
    if (currentThreshold !== actualValue) {
      await updateQualityGate(readGateField(liveGate, "id"), { threshold: actualValue });
      console.log(`Updated ${desiredGate.name} threshold to current value ${actualValue}.`);
    }
  }
}

for (const adrUid of config.trackedAdrs) {
  const adrFile = await findAdrPathByUid(adrUid);
  const repoAdr = parseRepoAdr(await readText(path.relative(repoRoot, adrFile)));
  const liveAdr = await getAdrByUid(adrUid, project);

  if (adrTitle(liveAdr) !== repoAdr.title || adrDecisionDate(liveAdr) !== repoAdr.decisionDate) {
    if (checkMode) {
      drift.push(`${adrUid} title/date drifted between repo and Ground Control.`);
    } else {
      await updateAdr(liveAdr.id, {
        title: repoAdr.title,
        decisionDate: repoAdr.decisionDate,
      });
      console.log(`Updated ${adrUid} title/date to match the repo ADR.`);
    }
  }

  const expectedStatus = normalizeAdrStatus(repoAdr.status);
  if (adrStatus(liveAdr) !== expectedStatus) {
    if (checkMode) {
      drift.push(`${adrUid} status drifted: repo=${expectedStatus} gc=${adrStatus(liveAdr)}`);
    } else {
      await transitionAdrStatus(liveAdr.id, expectedStatus);
      console.log(`Transitioned ${adrUid} to ${expectedStatus}.`);
    }
  }
}

if (drift.length > 0) {
  console.error("Ground Control policy drift detected:");
  for (const item of drift) {
    console.error(`- ${item}`);
  }
  process.exit(1);
}

console.log(applyMode ? "Ground Control policy synchronized." : "Ground Control policy is in sync.");
