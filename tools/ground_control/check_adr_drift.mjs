import path from "node:path";
import process from "node:process";

import { getAdrByUid } from "../../mcp/ground-control/lib.js";
import {
  adrDecisionDate,
  adrStatus,
  adrTitle,
  findAdrPathByUid,
  normalizeAdrStatus,
  parseRepoAdr,
  readJson,
  readText,
  repoRoot,
} from "./common.mjs";

const config = await readJson("tools/ground_control/policy.json");
const project = config.project;
const drift = [];

for (const adrUid of config.trackedAdrs) {
  const adrPath = await findAdrPathByUid(adrUid);
  const markdown = await readText(path.relative(repoRoot, adrPath));
  const repoAdr = parseRepoAdr(markdown);
  const liveAdr = await getAdrByUid(adrUid, project);

  if (adrTitle(liveAdr) !== repoAdr.title) {
    drift.push(`${adrUid} title drifted: repo='${repoAdr.title}' gc='${adrTitle(liveAdr)}'`);
  }
  if (adrDecisionDate(liveAdr) !== repoAdr.decisionDate) {
    drift.push(`${adrUid} date drifted: repo=${repoAdr.decisionDate} gc=${adrDecisionDate(liveAdr)}`);
  }
  if (adrStatus(liveAdr) !== normalizeAdrStatus(repoAdr.status)) {
    drift.push(`${adrUid} status drifted: repo=${normalizeAdrStatus(repoAdr.status)} gc=${adrStatus(liveAdr)}`);
  }
}

if (drift.length > 0) {
  console.error("ADR drift detected:");
  for (const item of drift) {
    console.error(`- ${item}`);
  }
  process.exit(1);
}

console.log("Repo ADR metadata matches Ground Control.");
