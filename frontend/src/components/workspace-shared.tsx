import type { WorkspaceAsset, WorkspaceLink } from "@/types/api";
/**
 * Shared building blocks for the threat-modeling and risk-scenario workspace
 * pages. Both pages render the same scoped-assets table, the same "As of" date
 * control, the same status <select>, the same linked-entity lists, and badges
 * with identical structure (only their colour/label maps differ). Extracting
 * these here keeps the two pages behaviourally identical while removing the
 * literal duplication between them.
 */
import type { ReactNode } from "react";

// ── Indicator badge ──────────────────────────────────────────────────────────

/**
 * Generic state-driven badge. `styleMap`/`labelMap` are keyed by the same state
 * value; `ariaPrefix` produces the `${ariaPrefix}: ${state}` aria-label used by
 * both the freshness and review badges.
 */
export function IndicatorBadge<S extends string>({
  state,
  styleMap,
  labelMap,
  ariaPrefix,
}: {
  state: S;
  styleMap: Record<S, string>;
  labelMap: Record<S, string>;
  ariaPrefix: string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${styleMap[state]}`}
      aria-label={`${ariaPrefix}: ${state}`}
    >
      {labelMap[state]}
    </span>
  );
}

// ── Asset row + section ───────────────────────────────────────────────────────

export function AssetRow({ asset }: { asset: WorkspaceAsset }) {
  return (
    <tr className="border-b border-border last:border-0">
      <td className="py-2 pr-4 font-mono text-sm">{asset.uid}</td>
      <td className="py-2 pr-4 text-sm">{asset.name}</td>
      <td className="py-2 pr-4 text-xs text-muted-foreground">
        {asset.assetType}
      </td>
      <td className="py-2 text-xs">
        {asset.boundary && (
          <span className="rounded bg-blue-100 px-1.5 py-0.5 text-blue-800">
            Boundary
          </span>
        )}
      </td>
    </tr>
  );
}

/**
 * The scoped-assets section: heading with count, empty state, and the asset
 * table. Identical on both workspace pages.
 */
export function AssetsSection({
  assets,
  count,
}: { assets: WorkspaceAsset[]; count: number }) {
  return (
    <section aria-labelledby="assets-heading">
      <h2 id="assets-heading" className="mb-2 text-lg font-medium">
        Assets
        <span className="ml-2 text-sm font-normal text-muted-foreground">
          ({count})
        </span>
      </h2>
      {assets.length === 0 ? (
        <p className="text-sm text-muted-foreground">No assets in scope.</p>
      ) : (
        <div className="overflow-auto rounded-lg border border-border">
          <table className="w-full text-left">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <th className="px-3 py-2">UID</th>
                <th className="px-3 py-2">Name</th>
                <th className="px-3 py-2">Type</th>
                <th className="px-3 py-2">Flags</th>
              </tr>
            </thead>
            <tbody className="px-3">
              {assets.map((asset) => (
                <AssetRow key={asset.id} asset={asset} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ── Linked-entity list ────────────────────────────────────────────────────────

/**
 * Renders a labelled list of linked entities, each as a link (when a URL is
 * present) or plain text, falling back through title → identifier → entity id.
 * Returns null when there are no links.
 */
export function WorkspaceLinkList({
  heading,
  links,
}: {
  heading: string;
  links: WorkspaceLink[];
}) {
  if (links.length === 0) return null;
  return (
    <div className="mt-2">
      <p className="mb-1 text-xs font-medium text-muted-foreground">
        {heading}
      </p>
      <ul className="space-y-0.5">
        {links.map((link, i) => (
          <li key={link.targetEntityId ?? i} className="text-xs">
            {link.targetUrl ? (
              <a
                href={link.targetUrl}
                className="text-primary underline"
                target="_blank"
                rel="noreferrer"
              >
                {link.targetTitle ??
                  link.targetIdentifier ??
                  link.targetEntityId}
              </a>
            ) : (
              <span>
                {link.targetTitle ??
                  link.targetIdentifier ??
                  link.targetEntityId}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

// ── Scope controls ────────────────────────────────────────────────────────────

/**
 * Status <select> shared by both workspaces. Generic over the concrete status
 * union so each page keeps its own typed value/onChange.
 */
export function WorkspaceStatusSelect<S extends string>({
  value,
  options,
  onChange,
}: {
  value: S | undefined;
  options: readonly S[];
  onChange: (value: S | undefined) => void;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium">Status</label>
      <select
        className="rounded border border-border bg-background px-2 py-1 text-sm"
        value={value ?? ""}
        onChange={(e) => onChange((e.target.value as S) || undefined)}
      >
        <option value="">All</option>
        {options.map((s) => (
          <option key={s} value={s}>
            {s}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * The "As of (ISO date)" datetime-local control shared by both workspaces. The
 * value is the leading 16 chars of the ISO timestamp; on change it is suffixed
 * back to a `:00Z` UTC timestamp (or cleared).
 */
export function AsOfDateControl({
  value,
  onChange,
}: {
  value: string | undefined;
  onChange: (value: string | undefined) => void;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium">As of (ISO date)</label>
      <input
        type="datetime-local"
        className="rounded border border-border bg-background px-2 py-1 text-sm"
        value={value?.slice(0, 16) ?? ""}
        onChange={(e) =>
          onChange(e.target.value ? `${e.target.value}:00Z` : undefined)
        }
      />
    </div>
  );
}

/** Wrapper that lays out the scope-control fields identically on both pages. */
export function ScopeControlsShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      {children}
    </div>
  );
}

// ── Loading / error states ────────────────────────────────────────────────────

export function WorkspaceLoading() {
  return (
    <div className="flex min-h-[20vh] items-center justify-center text-muted-foreground">
      Loading workspace&hellip;
    </div>
  );
}

export function WorkspaceError({ error }: { error: unknown }) {
  return (
    <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive">
      {error instanceof Error ? error.message : "Failed to load workspace."}
    </div>
  );
}

// ── Page shell ────────────────────────────────────────────────────────────────

/**
 * Shared page scaffold for both workspaces: title + description header, a
 * scope-controls slot, the loading and error states, and the data content
 * (rendered only once `hasData` is true). Each page passes its own typed
 * controls and content via slots/children; the surrounding chrome is identical.
 */
export function WorkspaceShell({
  title,
  description,
  controls,
  isLoading,
  isError,
  error,
  hasData,
  children,
}: {
  title: string;
  description: ReactNode;
  controls: ReactNode;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  hasData: boolean;
  children: ReactNode;
}) {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      </div>

      {controls}

      {isLoading && <WorkspaceLoading />}

      {isError && <WorkspaceError error={error} />}

      {hasData && <>{children}</>}
    </div>
  );
}
