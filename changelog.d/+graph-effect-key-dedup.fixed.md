### Fixed: deduplicate `effect` key in frontend graph property bag

`src/pages/graph.tsx` set `effect: getStringProperty(node, "effect")` twice in
the same object literal (once under the RiskScenario block, once under
THREAT_MODEL), triggering TypeScript TS1117 "object literal cannot have
multiple properties with the same name" and breaking the frontend build.
Both threat models and risk scenarios surface the same `effect` graph
property, so the property bag already shared one entry; the second
assignment was a stray copy. Removed the duplicate.
