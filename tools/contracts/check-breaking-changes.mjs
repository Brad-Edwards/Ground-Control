#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const repoRoot = resolve(import.meta.dirname, "../..");
const contractPath = "contracts/openapi/openapi.json";
const changesPath = "contracts/CHANGES.md";
const baseRef = process.env.BASE_REF || "origin/dev";

function readJson(path) {
  return JSON.parse(readFileSync(resolve(repoRoot, path), "utf8"));
}

function readBaseJson(path) {
  try {
    const text = execFileSync("git", ["show", `${baseRef}:${path}`], {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function hasBreakingDeclaration() {
  let text = "";
  try {
    text = readFileSync(resolve(repoRoot, changesPath), "utf8");
  } catch {
    return false;
  }
  return /(^|\n)##\s+\S+/.test(text) && /\bBREAKING\b/i.test(text) && /\bdeprecat/i.test(text);
}

function schemaType(schema) {
  if (!schema || typeof schema !== "object") return "unknown";
  if (schema.$ref) return schema.$ref;
  if (schema.enum) return `enum:${schema.enum.join("|")}`;
  if (schema.type) return `${schema.type}${schema.format ? `:${schema.format}` : ""}`;
  if (schema.allOf) return `allOf:${schema.allOf.map(schemaType).join("&")}`;
  if (schema.oneOf) return `oneOf:${schema.oneOf.map(schemaType).join("|")}`;
  if (schema.anyOf) return `anyOf:${schema.anyOf.map(schemaType).join("|")}`;
  return "unknown";
}

function schemaProperties(spec, name) {
  return spec.components?.schemas?.[name]?.properties ?? {};
}

function requiredSet(spec, name) {
  return new Set(spec.components?.schemas?.[name]?.required ?? []);
}

function enumValues(prop) {
  if (prop?.enum) return prop.enum;
  if (prop?.items?.enum) return prop.items.enum;
  return null;
}

function collectBreaks(base, head) {
  const breaks = [];
  const basePaths = base.paths ?? {};
  const headPaths = head.paths ?? {};

  for (const path of Object.keys(basePaths)) {
    if (!headPaths[path]) {
      breaks.push(`removed path ${path}`);
      continue;
    }
    for (const method of Object.keys(basePaths[path])) {
      if (!headPaths[path][method]) {
        breaks.push(`removed operation ${method.toUpperCase()} ${path}`);
      }
    }
  }

  const baseSchemas = base.components?.schemas ?? {};
  const headSchemas = head.components?.schemas ?? {};
  for (const schemaName of Object.keys(baseSchemas)) {
    if (!headSchemas[schemaName]) {
      breaks.push(`removed schema ${schemaName}`);
      continue;
    }
    const beforeProps = schemaProperties(base, schemaName);
    const afterProps = schemaProperties(head, schemaName);
    for (const propName of Object.keys(beforeProps)) {
      if (!afterProps[propName]) {
        breaks.push(`removed field ${schemaName}.${propName}`);
        continue;
      }
      const beforeType = schemaType(beforeProps[propName]);
      const afterType = schemaType(afterProps[propName]);
      if (beforeType !== afterType) {
        breaks.push(`changed type ${schemaName}.${propName}: ${beforeType} -> ${afterType}`);
      }
      const beforeEnum = enumValues(beforeProps[propName]);
      const afterEnum = enumValues(afterProps[propName]);
      if (beforeEnum && afterEnum) {
        const removed = beforeEnum.filter((value) => !afterEnum.includes(value));
        if (removed.length > 0) {
          breaks.push(`narrowed enum ${schemaName}.${propName}: removed ${removed.join(", ")}`);
        }
      }
    }

    const beforeRequired = requiredSet(base, schemaName);
    const afterRequired = requiredSet(head, schemaName);
    for (const propName of afterRequired) {
      if (!beforeRequired.has(propName)) {
        breaks.push(`tightened required field ${schemaName}.${propName}`);
      }
    }
  }

  return breaks;
}

const base = readBaseJson(contractPath);
if (!base) {
  console.log(`contract-breaking: ${contractPath} not present at ${baseRef}; treating this as initial contract publication.`);
  process.exit(0);
}

const head = readJson(contractPath);
const breaks = collectBreaks(base, head);
if (breaks.length === 0) {
  console.log("contract-breaking: no breaking OpenAPI changes detected.");
  process.exit(0);
}

if (hasBreakingDeclaration()) {
  console.log(`contract-breaking: ${breaks.length} breaking change(s) declared in ${changesPath}.`);
  process.exit(0);
}

console.error("contract-breaking: undeclared breaking OpenAPI changes detected:");
for (const item of breaks) {
  console.error(`- ${item}`);
}
console.error(`Add a BREAKING entry with a deprecation/migration record to ${changesPath}.`);
process.exit(1);
