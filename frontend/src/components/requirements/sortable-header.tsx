import { cn } from "@/lib/utils";
import { ChevronDown, ChevronUp } from "lucide-react";

export type SortField =
  | "uid"
  | "title"
  | "requirementType"
  | "priority"
  | "status"
  | "wave"
  | "updatedAt";

export function SortableHeader({
  field,
  label,
  currentSort,
  sortDir,
  onSort,
}: {
  field: SortField;
  label: string;
  currentSort: SortField;
  sortDir: "asc" | "desc";
  onSort: (f: SortField) => void;
}) {
  const isActive = currentSort === field;
  return (
    <th
      className="px-3 py-3 text-left"
      aria-sort={
        isActive ? (sortDir === "asc" ? "ascending" : "descending") : "none"
      }
    >
      <button
        type="button"
        className={cn(
          "inline-flex items-center gap-1 rounded text-xs font-medium text-muted-foreground cursor-pointer select-none hover:text-foreground",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          isActive && "text-foreground",
        )}
        onClick={() => onSort(field)}
      >
        {label}
        {isActive &&
          (sortDir === "asc" ? (
            <ChevronUp className="h-3 w-3" aria-hidden />
          ) : (
            <ChevronDown className="h-3 w-3" aria-hidden />
          ))}
      </button>
    </th>
  );
}
