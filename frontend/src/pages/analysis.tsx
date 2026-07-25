import { StatusBadge } from "@/components/ui/badge";
import { useProjectContext } from "@/contexts/project-context";
import {
  useCompleteness,
  useConsistencyViolations,
  useCoverageGaps,
  useCrossWave,
  useCycles,
  useOrphans,
} from "@/hooks/use-analysis";
import type { CompletenessIssueResponse, LinkType } from "@/types/api";
import { LINK_TYPES } from "@/types/api";
import * as Tabs from "@radix-ui/react-tabs";
import { Activity } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";

export function Analysis() {
  const { activeProject, isLoading } = useProjectContext();

  if (isLoading) {
    return <div className="h-8 w-48 animate-pulse rounded bg-muted" />;
  }

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <Activity className="h-12 w-12 text-muted-foreground" />
        <h1 className="text-2xl font-semibold">Analysis</h1>
        <p className="text-muted-foreground">
          Select a project to view analysis.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Analysis</h1>

      <Tabs.Root defaultValue="cycles">
        <Tabs.List className="flex gap-1 border-b border-border">
          {[
            "cycles",
            "orphans",
            "coverage",
            "cross-wave",
            "consistency",
            "completeness",
          ].map((tab) => (
            <Tabs.Trigger
              key={tab}
              value={tab}
              className="border-b-2 border-transparent px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground data-[state=active]:border-primary data-[state=active]:text-foreground capitalize"
            >
              {tab.replace("-", " ")}
            </Tabs.Trigger>
          ))}
        </Tabs.List>

        <Tabs.Content value="cycles" className="pt-4">
          <CyclesTab />
        </Tabs.Content>
        <Tabs.Content value="orphans" className="pt-4">
          <OrphansTab />
        </Tabs.Content>
        <Tabs.Content value="coverage" className="pt-4">
          <CoverageTab />
        </Tabs.Content>
        <Tabs.Content value="cross-wave" className="pt-4">
          <CrossWaveTab />
        </Tabs.Content>
        <Tabs.Content value="consistency" className="pt-4">
          <ConsistencyTab />
        </Tabs.Content>
        <Tabs.Content value="completeness" className="pt-4">
          <CompletenessTab />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
}

function CyclesTab() {
  const { data: cycles = [], isLoading } = useCycles();

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  if (cycles.length === 0) {
    return (
      <p className="text-sm text-green-400">No dependency cycles detected.</p>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-destructive font-medium">
        {cycles.length} cycle(s) detected
      </p>
      {cycles.map((cycle) => {
        const cycleKey = cycle.members.join(",");
        return (
          <div
            key={cycleKey}
            className="rounded-lg border border-red-500/20 bg-red-500/5 p-4"
          >
            <p className="text-sm font-medium mb-2">
              {cycle.members.length} members
            </p>
            <div className="flex flex-wrap gap-2 mb-3">
              {cycle.members.map((uid) => (
                <span
                  key={uid}
                  className="font-mono text-xs bg-accent px-2 py-1 rounded"
                >
                  {uid}
                </span>
              ))}
            </div>
            {cycle.edges.length > 0 && (
              <div className="text-xs text-muted-foreground space-y-1">
                {cycle.edges.map((edge) => (
                  <div key={`${edge.sourceUid}-${edge.targetUid}`}>
                    {edge.sourceUid} &rarr; {edge.targetUid}{" "}
                    <span className="text-xs">({edge.relationType})</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function OrphansTab() {
  const { projectId } = useParams<{ projectId: string }>();
  const { data: orphans = [], isLoading } = useOrphans();

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  if (orphans.length === 0) {
    return <p className="text-sm text-green-400">No orphan requirements.</p>;
  }

  return (
    <div className="space-y-2">
      <p className="text-sm text-muted-foreground mb-3">
        {orphans.length} requirement(s) with no relations
      </p>
      {orphans.map((r) => (
        <Link
          key={r.id}
          to={`/p/${projectId}/requirements/${r.id}`}
          className="flex items-center gap-3 rounded-lg border border-border bg-card p-3 hover:bg-accent/30"
        >
          <span className="font-mono text-xs text-muted-foreground">
            {r.uid}
          </span>
          <span className="text-sm font-medium">{r.title}</span>
          <StatusBadge status={r.status} />
          <span className="ml-auto text-xs text-muted-foreground">
            Wave {r.wave}
          </span>
        </Link>
      ))}
    </div>
  );
}

function CoverageTab() {
  const { projectId } = useParams<{ projectId: string }>();
  const [linkType, setLinkType] = useState<LinkType>("IMPLEMENTS");
  const { data: gaps = [], isLoading } = useCoverageGaps(linkType);

  return (
    <div className="space-y-4">
      <label className="flex items-center gap-3">
        <span className="text-sm font-medium">Link type:</span>
        <select
          className="rounded-md border border-input bg-background px-3 py-1.5 text-sm"
          value={linkType}
          onChange={(e) => setLinkType(e.target.value as LinkType)}
        >
          {LINK_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </label>

      {isLoading ? (
        <div className="animate-pulse h-20 bg-muted rounded" />
      ) : gaps.length === 0 ? (
        <p className="text-sm text-green-400">
          All requirements have {linkType} links.
        </p>
      ) : (
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">
            {gaps.length} requirement(s) missing {linkType} links
          </p>
          {gaps.map((r) => (
            <Link
              key={r.id}
              to={`/p/${projectId}/requirements/${r.id}`}
              className="flex items-center gap-3 rounded-lg border border-border bg-card p-3 hover:bg-accent/30"
            >
              <span className="font-mono text-xs text-muted-foreground">
                {r.uid}
              </span>
              <span className="text-sm font-medium">{r.title}</span>
              <StatusBadge status={r.status} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

function CrossWaveTab() {
  const { projectId } = useParams<{ projectId: string }>();
  const { data: violations = [], isLoading } = useCrossWave();

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  if (violations.length === 0) {
    return <p className="text-sm text-green-400">No cross-wave violations.</p>;
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">
        {violations.length} relation(s) where source wave &gt; target wave
      </p>
      <div className="rounded-lg border border-border overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-card border-b border-border">
            <tr>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Source
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Wave
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Relation
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Target
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Wave
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {violations.map((v) => (
              <tr key={v.id} className="hover:bg-accent/30">
                <td className="px-3 py-2">
                  <Link
                    to={`/p/${projectId}/requirements/${v.sourceId}`}
                    className="font-mono text-xs text-primary hover:underline"
                  >
                    {v.sourceUid}
                  </Link>
                </td>
                <td className="px-3 py-2 text-xs text-red-400 font-medium">
                  {v.sourceWave}
                </td>
                <td className="px-3 py-2 text-xs">
                  {v.relationType.replace(/_/g, " ")}
                </td>
                <td className="px-3 py-2">
                  <Link
                    to={`/p/${projectId}/requirements/${v.targetId}`}
                    className="font-mono text-xs text-primary hover:underline"
                  >
                    {v.targetUid}
                  </Link>
                </td>
                <td className="px-3 py-2 text-xs text-muted-foreground">
                  {v.targetWave}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ConsistencyTab() {
  const { projectId } = useParams<{ projectId: string }>();
  const { data: violations = [], isLoading } = useConsistencyViolations();

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  if (violations.length === 0) {
    return <p className="text-sm text-green-400">No consistency violations.</p>;
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">
        {violations.length} consistency violation(s) detected
      </p>
      <div className="rounded-lg border border-border overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-card border-b border-border">
            <tr>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Source
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Status
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Relation
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Target
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Status
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                Violation
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {violations.map((v) => (
              <tr key={v.relationId} className="hover:bg-accent/30">
                <td className="px-3 py-2">
                  <Link
                    to={`/p/${projectId}/requirements/${v.sourceId}`}
                    className="font-mono text-xs text-primary hover:underline"
                  >
                    {v.sourceUid}
                  </Link>
                </td>
                <td className="px-3 py-2">
                  <StatusBadge status={v.sourceStatus} />
                </td>
                <td className="px-3 py-2 text-xs">
                  {v.relationType.replace(/_/g, " ")}
                </td>
                <td className="px-3 py-2">
                  <Link
                    to={`/p/${projectId}/requirements/${v.targetId}`}
                    className="font-mono text-xs text-primary hover:underline"
                  >
                    {v.targetUid}
                  </Link>
                </td>
                <td className="px-3 py-2">
                  <StatusBadge status={v.targetStatus} />
                </td>
                <td className="px-3 py-2 text-xs text-red-400 font-medium">
                  {v.violationType.replace(/_/g, " ")}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function CompletenessTab() {
  const { data, isLoading } = useCompleteness();

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  if (!data || data.total === 0) {
    return (
      <p className="text-sm text-muted-foreground">No requirements found.</p>
    );
  }

  const statusEntries = Object.entries(data.byStatus);

  return (
    <div className="space-y-6">
      <div>
        <p className="text-sm font-medium mb-3">
          {data.total} requirement(s) total
        </p>
        <div className="flex flex-wrap gap-3">
          {statusEntries.map(([status, count]) => (
            <div
              key={status}
              className="rounded-lg border border-border bg-card px-4 py-3 text-center"
            >
              <p className="text-lg font-semibold">{count}</p>
              <p className="text-xs text-muted-foreground">{status}</p>
            </div>
          ))}
        </div>
      </div>

      {data.issues.length === 0 ? (
        <p className="text-sm text-green-400">
          All requirements have title and statement.
        </p>
      ) : (
        <div>
          <p className="text-sm text-destructive font-medium mb-3">
            {data.issues.length} issue(s) found
          </p>
          <div className="rounded-lg border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-card border-b border-border">
                <tr>
                  <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                    UID
                  </th>
                  <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                    Issue
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {data.issues.map((issue: CompletenessIssueResponse) => (
                  <tr
                    key={`${issue.uid}-${issue.issue}`}
                    className="hover:bg-accent/30"
                  >
                    <td className="px-3 py-2 font-mono text-xs">{issue.uid}</td>
                    <td className="px-3 py-2 text-xs text-red-400">
                      {issue.issue}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
