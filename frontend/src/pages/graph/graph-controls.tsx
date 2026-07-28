// Split from graph.tsx under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). The markup is unchanged.
//
// The colour/layout/view controls, lifted out of the page's exported component.
// That component has to stay at its own path for routing, so the page could not
// be brought under the limit by moving whole declarations out.

import type { ColorScheme, LayoutId } from "@/lib/graph-constants";
import { Maximize, RotateCcw } from "lucide-react";

export interface GraphControlsProps {
  colorScheme: ColorScheme;
  setColorScheme: (value: ColorScheme) => void;
  layoutId: LayoutId;
  setLayoutId: (value: LayoutId) => void;
  handleFit: () => void;
  handleReset: () => void;
  runTraversal: () => void;
  fetchData: () => void;
  selectedNodeId: string;
  loading: boolean;
  viewMode: "visualization" | "traversal";
}

const SELECT_CLASS =
  "rounded border border-input bg-background px-2 py-1 text-xs text-foreground";
const BUTTON_CLASS =
  "flex items-center gap-1 rounded border border-input bg-background px-2 py-1 text-xs hover:border-primary";

export function GraphControls({
  colorScheme,
  setColorScheme,
  layoutId,
  setLayoutId,
  handleFit,
  handleReset,
  runTraversal,
  fetchData,
  selectedNodeId,
  loading,
  viewMode,
}: GraphControlsProps) {
  return (
    <div className="flex items-center gap-4 border-b border-border bg-card px-4 py-2">
      <div className="flex items-center gap-2">
        <label
          htmlFor="graph-color-by"
          className="text-xs text-muted-foreground"
        >
          Color by
        </label>
        <select
          id="graph-color-by"
          value={colorScheme}
          onChange={(e) => setColorScheme(e.target.value as ColorScheme)}
          className={SELECT_CLASS}
        >
          <option value="entity">Entity type</option>
          <option value="series">Series</option>
          <option value="priority">Priority</option>
          <option value="status">Status</option>
          <option value="wave">Wave</option>
        </select>
      </div>
      <div className="flex items-center gap-2">
        <label htmlFor="graph-layout" className="text-xs text-muted-foreground">
          Layout
        </label>
        <select
          id="graph-layout"
          value={layoutId}
          onChange={(e) => setLayoutId(e.target.value as LayoutId)}
          className={SELECT_CLASS}
        >
          <option value="dagre-lr">DAG (left to right)</option>
          <option value="dagre-tb">DAG (top to bottom)</option>
          <option value="dagre-wave-lr">Wave-ordered (L-R)</option>
          <option value="dagre-wave-tb">Wave-ordered (T-B)</option>
        </select>
      </div>
      <button
        type="button"
        onClick={handleFit}
        className={BUTTON_CLASS}
        title="Fit to screen"
      >
        <Maximize className="h-3 w-3" /> Fit
      </button>
      <button
        type="button"
        onClick={handleReset}
        className={BUTTON_CLASS}
        title="Reset filters"
      >
        <RotateCcw className="h-3 w-3" /> Reset
      </button>
      <button
        type="button"
        onClick={runTraversal}
        disabled={!selectedNodeId || loading}
        className={`${BUTTON_CLASS} disabled:cursor-not-allowed disabled:opacity-50`}
        title="Traverse two hops from the selected node"
      >
        <Maximize className="h-3 w-3" /> Focus selection
      </button>
      {viewMode === "traversal" && (
        <button
          type="button"
          onClick={fetchData}
          className={BUTTON_CLASS}
          title="Restore the full mixed-entity graph"
        >
          <RotateCcw className="h-3 w-3" /> Full graph
        </button>
      )}
    </div>
  );
}
