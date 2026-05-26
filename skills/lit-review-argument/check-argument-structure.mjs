#!/usr/bin/env node
// # phase-4 Argdown structural checker.
//
// Reads an Argdown JSON model (produced by `argdown json`) and runs structural
// checks that mechanically catch argument-construction failure modes an LLM
// does not reliably self-police:
//
//   A  ungrounded premise       — a PCS premise with no {evidence:} tag that is
//                                 also not derived as a conclusion elsewhere in
//                                 the map. (A premise tagged "paper-contribution"
//                                 is the paper's own analytic frame: listed,
//                                 not failed.)
//   B  unreconstructed support  — an argument wired in as support for a claim
//                                 but given no premise-conclusion structure.
//                                 Argdown exists to make logical structure
//                                 explicit; claimed support with no PCS is a
//                                 direct failure to do that. (A no-PCS argument
//                                 that only ATTACKS is a bare-stated objection
//                                 — acceptable, reported as info.)
//   C  unanswered objection     — a node that attacks a load-bearing claim and
//                                 has no response (no incoming attack on it).
//   D  circular support         — a statement that transitively supports itself.
//
// It does NOT check material validity — whether the premises actually entail
// the conclusion — or the truth of a premise. Those stay the agent's judgement
// (see lit-review-argument/SKILL.md, step 5).
//
// A malformed PCS (the inference line present but a premise or conclusion
// missing) is rejected by the Argdown parser at the syntax layer, before this
// checker runs — so it needs no check here.
//
// Usage: check-argument-structure.mjs <model.json>
// Exit:  0 clean · 1 one or more hard failures · 2 bad input

import { readFileSync } from 'node:fs';

const jf = process.argv[2];
if (!jf) {
  console.error('usage: check-argument-structure.mjs <model.json>');
  process.exit(2);
}
let m;
try {
  m = JSON.parse(readFileSync(jf, 'utf8'));
} catch (e) {
  console.error('FAIL [input]: cannot read JSON model: ' + e.message);
  process.exit(2);
}

const args = m.arguments || {};
const rels = m.relations || [];
const CONC = new Set(['main-conclusion', 'preliminary-conclusion']);
const pcsOf = (a) => ((a.members || []).find((x) => x.pcs && x.pcs.length) || {}).pcs || [];
const snip = (p) => (p.text || p.title || '?').trim().replace(/\s+/g, ' ').slice(0, 72);

const fails = [];
const info = [];

// statements derived inside the map (conclusions of some argument)
const conclusionTitles = new Set();
for (const a of Object.values(args))
  for (const p of pcsOf(a)) if (CONC.has(p.role) && p.title) conclusionTitles.add(p.title);

// ---- A: ungrounded premises ----
let premiseCount = 0;
let analyticFrame = 0;
for (const [name, a] of Object.entries(args)) {
  for (const p of pcsOf(a)) {
    if (p.role !== 'premise') continue;
    premiseCount++;
    const ev = p.data && p.data.evidence;
    if (typeof ev === 'string' && /paper-contribution/i.test(ev)) {
      analyticFrame++;
      info.push(`analytic-frame premise (defend in prose, not cited) — <${name}>: "${snip(p)}…"`);
    } else if (ev) {
      // grounded in the evidence base
    } else if (p.title && conclusionTitles.has(p.title)) {
      // grounded by derivation: it is a conclusion elsewhere in the map
    } else {
      fails.push(`A ungrounded premise — <${name}>: "${snip(p)}…" has no {evidence:} tag and is not derived in the map.`);
    }
  }
}

// ---- B: unreconstructed support arguments ----
// A no-PCS argument that is claimed to SUPPORT a claim (stands as the 'from' of
// a support relation) is hand-waved — it should be reconstructed. A no-PCS
// argument that only attacks is a bare-stated objection, which is acceptable.
const supportFrom = new Set(
  rels.filter((r) => r.relationType === 'support').map((r) => r.from)
);
for (const [name, a] of Object.entries(args)) {
  if (pcsOf(a).length > 0) continue;
  if (supportFrom.has(name))
    fails.push(`B unreconstructed support — <${name}> is wired in as support for a claim but has no premise-conclusion reconstruction.`);
  else
    info.push(`argument stated without a reconstruction (no PCS) — <${name}>`);
}

// ---- C: unanswered objections ----
// positive seed = every statement that participates in a PCS (premise/conclusion)
const positive = new Set();
for (const a of Object.values(args))
  for (const p of pcsOf(a)) if (p.title) positive.add(p.title);
const attacks = rels.filter((r) => r.relationType === 'attack');
const attacked = new Set(attacks.map((r) => r.to));
const objections = new Set();
for (const r of attacks) if (positive.has(r.to)) objections.add(r.from);
for (const o of objections) {
  if (!attacked.has(o))
    fails.push(`C unanswered objection — "${o}" attacks a load-bearing claim and has no response (no incoming attack).`);
}

// ---- D: circular support ----
const adj = new Map();
const edge = (u, v) => {
  if (!adj.has(u)) adj.set(u, []);
  adj.get(u).push(v);
};
for (const [name, a] of Object.entries(args)) {
  for (const p of pcsOf(a)) {
    if (p.role === 'premise' && p.title) edge(p.title, name);
    if (CONC.has(p.role) && p.title) edge(name, p.title);
  }
}
const color = new Map(); // 1 = on stack, 2 = done
let cycle = null;
const dfs = (u, path) => {
  color.set(u, 1);
  for (const v of adj.get(u) || []) {
    if (color.get(v) === 1) {
      cycle = [...path, u, v];
      return true;
    }
    if (!color.get(v) && dfs(v, [...path, u])) return true;
  }
  color.set(u, 2);
  return false;
};
for (const u of adj.keys()) {
  if (!color.get(u) && dfs(u, [])) break;
}
if (cycle) fails.push(`D circular support — ${cycle.join(' -> ')}`);

// ---- report ----
for (const i of info) console.log('  info: ' + i);
console.log(
  `  checked: ${Object.keys(args).length} arguments, ${premiseCount} premises, ` +
    `${attacks.length} attack relations, ${objections.size} objections.`
);
if (fails.length === 0) {
  console.log('  STRUCTURE OK — grounding, support-reconstruction, objection-closure, non-circularity all pass.');
  if (analyticFrame)
    console.log(`  (${analyticFrame} analytic-frame premise(s) listed above — the paper's frame, defended in prose, not a failure.)`);
  console.log('  NOTE: material validity — do the premises entail the conclusion — is NOT checked here; that is the agent pass.');
  process.exit(0);
} else {
  for (const f of fails) console.log('  FAIL: ' + f);
  console.log(`  STRUCTURE FAIL — ${fails.length} issue(s) above.`);
  process.exit(1);
}
