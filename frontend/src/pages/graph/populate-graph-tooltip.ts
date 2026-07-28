// Split from graph.tsx under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). The body is unchanged.
//
// Building the hover tooltip is plain DOM work with no component state, so it
// lives outside the page. graph.tsx keeps the `useCallback` wrapper because the
// render effect lists it as a dependency.

import {
  PRIORITY_COLORS,
  STATUS_COLORS,
  getEntityTypeColor,
} from "@/lib/graph-constants";
import {
  TOOLTIP_FIELDS_BY_ENTITY_TYPE,
  firstTooltipString,
  getTooltipValue,
} from "./graph-node-data";

export function getTooltipTags(
  data: Record<string, unknown>,
): Array<{ text: string; bg: string }> {
  const entityType = String(data.entityType ?? "UNKNOWN");
  const entityColor = getEntityTypeColor(entityType);

  if (entityType === "REQUIREMENT") {
    return [
      {
        text: String(data.priority ?? ""),
        bg: PRIORITY_COLORS[String(data.priority ?? "")] ?? "#555",
      },
      {
        text: String(data.status ?? ""),
        bg: STATUS_COLORS[String(data.status ?? "")] ?? "#555",
      },
      { text: `Wave ${Number(data.wave ?? 0)}`, bg: "#6c7ee1" },
      { text: String(data.type ?? ""), bg: "#4ecdc4" },
    ].filter((tag) => tag.text);
  }

  return (TOOLTIP_FIELDS_BY_ENTITY_TYPE[entityType] ?? [])
    .map((field) => {
      const value = getTooltipValue(data, field.key);
      return value
        ? { text: `${field.label}: ${value}`, bg: entityColor }
        : null;
    })
    .filter((tag): tag is { text: string; bg: string } => tag !== null);
}

export function populateGraphTooltip(
  container: HTMLDivElement,
  d: Record<string, unknown>,
) {
  container.replaceChildren();

  const uidDiv = document.createElement("div");
  uidDiv.style.cssText =
    "font-size:11px;color:#6c7ee1;font-weight:600;margin-bottom:4px";
  uidDiv.textContent = String(d.uid ?? d.entityType ?? d.id ?? "");
  container.appendChild(uidDiv);

  const titleDiv = document.createElement("div");
  titleDiv.style.cssText = "font-weight:600;margin-bottom:6px";
  titleDiv.textContent = String(d.title ?? d.label ?? "");
  container.appendChild(titleDiv);

  const metaDiv = document.createElement("div");
  metaDiv.style.cssText =
    "display:flex;gap:8px;margin-bottom:6px;flex-wrap:wrap";

  const typeTag = {
    text: String(d.entityType ?? "UNKNOWN"),
    bg: getEntityTypeColor(String(d.entityType ?? "")),
  };
  const detailTags = getTooltipTags(d);

  for (const t of [typeTag, ...detailTags].filter((tag) => tag.text)) {
    const span = document.createElement("span");
    span.style.cssText = `display:inline-block;padding:1px 6px;border-radius:3px;font-size:10px;font-weight:600;background:${t.bg}33;color:${t.bg}`;
    span.textContent = t.text;
    metaDiv.appendChild(span);
  }
  container.appendChild(metaDiv);

  const statement =
    String(d.entityType ?? "") === "REQUIREMENT"
      ? firstTooltipString(d, "statement")
      : firstTooltipString(d, "description", "observationValue", "effect");
  if (statement) {
    const stmtDiv = document.createElement("div");
    stmtDiv.style.cssText =
      "color:#a1a1aa;font-size:11px;line-height:1.4;max-height:80px;overflow:hidden";
    stmtDiv.textContent = statement;
    container.appendChild(stmtDiv);
  }
}
