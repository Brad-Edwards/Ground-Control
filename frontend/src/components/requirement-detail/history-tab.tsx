import { inputClass } from "@/components/ui/form-field";
import {
  type TimelineFilters,
  useRequirementTimeline,
} from "@/hooks/use-history";
import type {
  ChangeCategory,
  FieldChangeResponse,
  TimelineEntryResponse,
} from "@/types/api";
import { CHANGE_CATEGORIES } from "@/types/api";
import {
  ChevronDown,
  ChevronRight,
  FileText,
  GitBranch,
  Link2,
} from "lucide-react";
import { useMemo, useState } from "react";

const CATEGORY_LABELS: Record<ChangeCategory, string> = {
  REQUIREMENT: "Requirement",
  RELATION: "Relation",
  TRACEABILITY_LINK: "Traceability",
};

const CATEGORY_ICONS: Record<ChangeCategory, typeof FileText> = {
  REQUIREMENT: FileText,
  RELATION: GitBranch,
  TRACEABILITY_LINK: Link2,
};

const REVISION_COLORS: Record<string, string> = {
  ADD: "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
  MOD: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  DEL: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200",
};

const DOT_COLORS: Record<string, string> = {
  ADD: "bg-green-500",
  MOD: "bg-blue-500",
  DEL: "bg-red-500",
};

function formatFieldName(key: string): string {
  return key
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (s) => s.toUpperCase())
    .trim();
}

function formatFieldValue(value: unknown): string {
  if (value === null || value === undefined) return "(empty)";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function FieldDiff({
  changes,
}: {
  changes: Record<string, FieldChangeResponse>;
}) {
  return (
    <div className="mt-2 space-y-1.5">
      {Object.entries(changes).map(([key, change]) => (
        <div key={key} className="text-xs">
          <span className="font-medium text-foreground">
            {formatFieldName(key)}:
          </span>
          <div className="ml-4 flex flex-col gap-0.5">
            <span className="text-red-600 dark:text-red-400 line-through">
              {formatFieldValue(change.oldValue)}
            </span>
            <span className="text-green-600 dark:text-green-400">
              {formatFieldValue(change.newValue)}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}

function TimelineEntryCard({ entry }: { entry: TimelineEntryResponse }) {
  const [expanded, setExpanded] = useState(false);
  const Icon = CATEGORY_ICONS[entry.changeCategory];
  const hasChanges = entry.changes && Object.keys(entry.changes).length > 0;

  return (
    <div className="relative pl-8">
      {/* Timeline dot */}
      <div
        className={`absolute left-0 top-2 w-3 h-3 rounded-full border-2 border-background ${DOT_COLORS[entry.revisionType] ?? "bg-gray-400"}`}
      />

      <div className="rounded-lg border border-border bg-card p-3">
        {/* Header */}
        <div className="flex items-center gap-2 flex-wrap">
          <Icon className="h-3.5 w-3.5 text-muted-foreground" />
          <span className="text-xs font-medium text-muted-foreground">
            {CATEGORY_LABELS[entry.changeCategory]}
          </span>
          <span
            className={`text-xs font-medium px-1.5 py-0.5 rounded ${REVISION_COLORS[entry.revisionType] ?? ""}`}
          >
            {entry.revisionType}
          </span>
          <span className="text-xs text-muted-foreground ml-auto">
            {new Date(entry.timestamp).toLocaleString()}
          </span>
          {entry.actor && (
            <span className="text-xs text-muted-foreground">
              by {entry.actor}
            </span>
          )}
        </div>

        {/* Snapshot summary */}
        {entry.snapshot && (
          <div className="mt-1.5 text-xs text-muted-foreground">
            {entry.changeCategory === "REQUIREMENT" && (
              <span>{entry.snapshot.title as string}</span>
            )}
            {entry.changeCategory === "RELATION" && (
              <span>{entry.snapshot.relationType as string} relation</span>
            )}
            {entry.changeCategory === "TRACEABILITY_LINK" && (
              <span>
                {entry.snapshot.linkType as string}{" "}
                {entry.snapshot.artifactType as string}:{" "}
                {entry.snapshot.artifactIdentifier as string}
              </span>
            )}
          </div>
        )}

        {/* Expandable diff */}
        {hasChanges && (
          <>
            <button
              type="button"
              onClick={() => setExpanded(!expanded)}
              className="mt-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              {expanded ? (
                <ChevronDown className="h-3 w-3" />
              ) : (
                <ChevronRight className="h-3 w-3" />
              )}
              {Object.keys(entry.changes).length} field(s) changed
            </button>
            {expanded && <FieldDiff changes={entry.changes} />}
          </>
        )}
      </div>
    </div>
  );
}

export function HistoryTab({ requirementId }: { requirementId: string }) {
  const [filters, setFilters] = useState<TimelineFilters>({});
  const { data: timeline = [], isLoading } = useRequirementTimeline(
    requirementId,
    filters,
  );

  const activeCategories = useMemo(() => {
    const cats = new Set<ChangeCategory>();
    for (const e of timeline) {
      cats.add(e.changeCategory);
    }
    return cats;
  }, [timeline]);

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  return (
    <div className="space-y-4">
      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-4 rounded-lg border border-border bg-card p-3">
        <span className="text-xs font-medium text-muted-foreground">
          Filter:
        </span>
        <div className="flex items-center gap-3">
          {CHANGE_CATEGORIES.map((cat) => (
            <label key={cat} className="flex items-center gap-1.5 text-xs">
              <input
                type="radio"
                name="changeCategory"
                checked={filters.changeCategory === cat}
                onChange={() =>
                  setFilters((f) => ({
                    ...f,
                    changeCategory: f.changeCategory === cat ? undefined : cat,
                  }))
                }
                className="accent-primary"
              />
              {CATEGORY_LABELS[cat]}
            </label>
          ))}
          {filters.changeCategory && (
            <button
              type="button"
              onClick={() =>
                setFilters((f) => ({ ...f, changeCategory: undefined }))
              }
              className="text-xs text-muted-foreground hover:text-foreground"
            >
              Clear
            </button>
          )}
        </div>
        <div className="flex items-center gap-2 ml-auto">
          <label className="text-xs text-muted-foreground">From</label>
          <input
            type="date"
            value={
              filters.from
                ? new Date(filters.from).toISOString().split("T")[0]
                : ""
            }
            onChange={(e) =>
              setFilters((f) => ({
                ...f,
                from: e.target.value
                  ? new Date(e.target.value).toISOString()
                  : undefined,
              }))
            }
            className={`${inputClass} text-xs w-32`}
          />
          <label className="text-xs text-muted-foreground">To</label>
          <input
            type="date"
            value={
              filters.to ? new Date(filters.to).toISOString().split("T")[0] : ""
            }
            onChange={(e) =>
              setFilters((f) => ({
                ...f,
                to: e.target.value
                  ? new Date(`${e.target.value}T23:59:59.999Z`).toISOString()
                  : undefined,
              }))
            }
            className={`${inputClass} text-xs w-32`}
          />
        </div>
      </div>

      {/* Timeline */}
      {timeline.length === 0 ? (
        <p className="text-sm text-muted-foreground">No history entries.</p>
      ) : (
        <div className="relative">
          {/* Vertical line */}
          <div className="absolute left-[5px] top-2 bottom-2 w-0.5 bg-border" />
          <div className="space-y-3">
            {timeline.map((entry, idx) => (
              <TimelineEntryCard
                key={`${entry.changeCategory}-${entry.entityId}-${entry.revisionNumber}-${idx}`}
                entry={entry}
              />
            ))}
          </div>
        </div>
      )}

      {/* Legend */}
      {timeline.length > 0 && (
        <div className="flex items-center gap-4 text-xs text-muted-foreground pt-2 border-t border-border">
          <span>Legend:</span>
          {CHANGE_CATEGORIES.filter((cat) => activeCategories.has(cat)).map(
            (cat) => {
              const Icon = CATEGORY_ICONS[cat];
              return (
                <span key={cat} className="flex items-center gap-1">
                  <Icon className="h-3 w-3" /> {CATEGORY_LABELS[cat]}
                </span>
              );
            },
          )}
          <span className="ml-4 flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-green-500" /> Added
          </span>
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-blue-500" /> Modified
          </span>
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-red-500" /> Deleted
          </span>
        </div>
      )}
    </div>
  );
}
