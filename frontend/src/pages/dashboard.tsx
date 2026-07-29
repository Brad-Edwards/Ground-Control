import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/loading-state";
import { MetricCard } from "@/components/ui/metric-card";
import { PageHeader } from "@/components/ui/page-header";
import { useProjectContext } from "@/contexts/project-context";
import {
  useCoverageGaps,
  useCrossWave,
  useCycles,
  useDashboardStats,
  useOrphans,
} from "@/hooks/use-analysis";
import { cn } from "@/lib/utils";
import type { DashboardStatsResponse, RecentChangeResponse } from "@/types/api";
import {
  AlertTriangle,
  ArrowRight,
  Clock,
  GitFork,
  Layers,
  Link2Off,
  Rocket,
  Unlink,
} from "lucide-react";
import { useNavigate, useParams } from "react-router";

type MetricTone = "default" | "info" | "success" | "warning" | "danger";

export function Dashboard() {
  const { activeProject, isLoading } = useProjectContext();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();

  if (isLoading) return <LoadingSkeleton />;

  if (!activeProject) {
    return (
      <EmptyState
        icon={Rocket}
        title="Welcome to Ground Control"
        description="Select a project from the header to get started."
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={activeProject.name}
        description={activeProject.description || undefined}
      />
      <DashboardContent navigate={navigate} projectId={projectId ?? ""} />
    </div>
  );
}

function DashboardContent({
  navigate,
  projectId,
}: {
  navigate: (path: string) => void;
  projectId: string;
}) {
  const { data: stats, isLoading } = useDashboardStats();

  if (isLoading || !stats) {
    return <LoadingSkeleton />;
  }

  return (
    <div className="space-y-6">
      <StatusOverview stats={stats} navigate={navigate} projectId={projectId} />
      <WaveProgress stats={stats} navigate={navigate} projectId={projectId} />
      <TraceabilityCoverage
        stats={stats}
        navigate={navigate}
        projectId={projectId}
      />
      <RecentChanges
        changes={stats.recentChanges}
        navigate={navigate}
        projectId={projectId}
      />
      <AnalysisAlerts navigate={navigate} projectId={projectId} />
    </div>
  );
}

function StatusOverview({
  stats,
  navigate,
  projectId,
}: {
  stats: DashboardStatsResponse;
  navigate: (path: string) => void;
  projectId: string;
}) {
  const statCards: {
    label: string;
    value: number;
    tone: MetricTone;
    filter?: string;
  }[] = [
    { label: "Total", value: stats.totalRequirements, tone: "default" },
    {
      label: "Draft",
      value: stats.byStatus.DRAFT ?? 0,
      tone: "default",
      filter: "DRAFT",
    },
    {
      label: "Active",
      value: stats.byStatus.ACTIVE ?? 0,
      tone: "success",
      filter: "ACTIVE",
    },
    {
      label: "Deprecated",
      value: stats.byStatus.DEPRECATED ?? 0,
      tone: "warning",
      filter: "DEPRECATED",
    },
    {
      label: "Archived",
      value: stats.byStatus.ARCHIVED ?? 0,
      tone: "default",
      filter: "ARCHIVED",
    },
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
      {statCards.map((s) => (
        <MetricCard
          key={s.label}
          label={s.label}
          value={s.value}
          tone={s.tone}
          onClick={() =>
            navigate(
              s.filter
                ? `/p/${projectId}/requirements?status=${s.filter}`
                : `/p/${projectId}/requirements`,
            )
          }
        />
      ))}
    </div>
  );
}

const STATUS_BAR_COLORS: Record<string, string> = {
  DRAFT: "bg-muted-foreground",
  ACTIVE: "bg-success",
  DEPRECATED: "bg-warning",
  ARCHIVED: "bg-muted-foreground/50",
};

function WaveProgress({
  stats,
  navigate,
  projectId,
}: {
  stats: DashboardStatsResponse;
  navigate: (path: string) => void;
  projectId: string;
}) {
  if (stats.byWave.length === 0) return null;

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-medium">Wave Progress</h2>
      <div className="space-y-2">
        {stats.byWave.map((wave) => (
          <button
            key={wave.wave ?? "unassigned"}
            type="button"
            className="flex w-full items-center gap-4 rounded-lg border border-border bg-card p-3 text-left transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() =>
              navigate(
                wave.wave != null
                  ? `/p/${projectId}/requirements?wave=${wave.wave}`
                  : `/p/${projectId}/requirements`,
              )
            }
          >
            <span className="w-24 shrink-0 text-sm font-medium text-muted-foreground">
              {wave.wave != null ? `Wave ${wave.wave}` : "Unassigned"}
            </span>
            <div className="flex h-4 flex-1 overflow-hidden rounded-full bg-muted">
              {Object.entries(wave.byStatus).map(([status, count]) => (
                <div
                  key={status}
                  className={cn(
                    "h-full",
                    STATUS_BAR_COLORS[status] ?? "bg-info",
                  )}
                  style={{ width: `${(count / wave.total) * 100}%` }}
                  title={`${status}: ${count}`}
                />
              ))}
            </div>
            <span className="w-10 shrink-0 text-right text-sm font-medium">
              {wave.total}
            </span>
          </button>
        ))}
      </div>
      <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
        {Object.entries(STATUS_BAR_COLORS).map(([status, color]) => (
          <span key={status} className="flex items-center gap-1">
            <span
              className={cn("inline-block h-2.5 w-2.5 rounded-full", color)}
            />
            {status}
          </span>
        ))}
      </div>
    </div>
  );
}

function coverageTone(percentage: number): string {
  if (percentage >= 80) return "bg-success";
  if (percentage >= 50) return "bg-warning";
  return "bg-danger";
}

function TraceabilityCoverage({
  stats,
  navigate,
  projectId,
}: {
  stats: DashboardStatsResponse;
  navigate: (path: string) => void;
  projectId: string;
}) {
  const entries = Object.entries(stats.coverageByLinkType);
  if (entries.length === 0) return null;

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-medium">Traceability Coverage</h2>
      <div className="space-y-2">
        {entries.map(([linkType, cov]) => (
          <button
            key={linkType}
            type="button"
            className="flex w-full items-center gap-4 rounded-lg border border-border bg-card p-3 text-left transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() => navigate(`/p/${projectId}/analysis`)}
          >
            <span className="w-28 shrink-0 text-sm font-medium text-muted-foreground">
              {linkType}
            </span>
            <div className="flex h-4 flex-1 overflow-hidden rounded-full bg-muted">
              <div
                className={cn(
                  "h-full rounded-full",
                  coverageTone(cov.percentage),
                )}
                style={{ width: `${cov.percentage}%` }}
              />
            </div>
            <span className="w-20 shrink-0 text-right text-sm font-medium">
              {cov.covered}/{cov.total} ({cov.percentage}%)
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

const REVISION_TYPE_VARIANT: Record<string, BadgeVariant> = {
  ADD: "success",
  MOD: "info",
  DEL: "danger",
};

function formatRelativeTime(timestamp: string): string {
  const now = Date.now();
  const then = new Date(timestamp).getTime();
  const diffMs = now - then;
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return "just now";
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDays = Math.floor(diffHr / 24);
  return `${diffDays}d ago`;
}

function RecentChanges({
  changes,
  navigate,
  projectId,
}: {
  changes: RecentChangeResponse[];
  navigate: (path: string) => void;
  projectId: string;
}) {
  if (changes.length === 0) return null;

  return (
    <div className="space-y-3">
      <h2 className="flex items-center gap-2 text-lg font-medium">
        <Clock className="h-5 w-5 text-muted-foreground" aria-hidden />
        Recent Changes
      </h2>
      <div className="space-y-1">
        {changes.map((change, idx) => (
          <button
            key={`${change.uid}-${idx}`}
            type="button"
            className="flex w-full items-center gap-3 rounded-lg border border-border bg-card px-3 py-2 text-left transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() =>
              navigate(
                `/p/${projectId}/requirements?search=${encodeURIComponent(change.uid)}`,
              )
            }
          >
            <Badge
              variant={REVISION_TYPE_VARIANT[change.revisionType] ?? "neutral"}
            >
              {change.revisionType}
            </Badge>
            <span className="shrink-0 font-mono text-sm text-muted-foreground">
              {change.uid}
            </span>
            <span className="min-w-0 flex-1 truncate text-sm">
              {change.title}
            </span>
            <span className="shrink-0 text-xs text-muted-foreground">
              {formatRelativeTime(change.timestamp)}
            </span>
            {change.actor && (
              <span className="shrink-0 text-xs text-muted-foreground">
                {change.actor}
              </span>
            )}
          </button>
        ))}
      </div>
    </div>
  );
}

function AnalysisAlerts({
  navigate,
  projectId,
}: {
  navigate: (path: string) => void;
  projectId: string;
}) {
  const { data: cycles } = useCycles();
  const { data: orphans } = useOrphans();
  const { data: coverageGaps } = useCoverageGaps("IMPLEMENTS");
  const { data: crossWave } = useCrossWave();

  const alerts: {
    icon: typeof GitFork;
    label: string;
    count: number;
    tone: MetricTone;
    accent: string;
    path: string;
  }[] = [
    {
      icon: GitFork,
      label: "Dependency Cycles",
      count: cycles?.length ?? 0,
      tone: "danger",
      accent: "border-danger/20 bg-danger/5 text-danger",
      path: `/p/${projectId}/analysis`,
    },
    {
      icon: Unlink,
      label: "Orphan Requirements",
      count: orphans?.length ?? 0,
      tone: "warning",
      accent: "border-warning/20 bg-warning/5 text-warning",
      path: `/p/${projectId}/analysis`,
    },
    {
      icon: Link2Off,
      label: "Missing IMPLEMENTS Links",
      count: coverageGaps?.length ?? 0,
      tone: "warning",
      accent: "border-warning/20 bg-warning/5 text-warning",
      path: `/p/${projectId}/analysis`,
    },
    {
      icon: Layers,
      label: "Cross-Wave Violations",
      count: crossWave?.length ?? 0,
      tone: "evidence" as MetricTone,
      accent: "border-evidence/20 bg-evidence/5 text-evidence",
      path: `/p/${projectId}/analysis`,
    },
  ];

  const hasAlerts = alerts.some((a) => a.count > 0);

  if (!hasAlerts) {
    return (
      <div className="rounded-lg border border-success/20 bg-success/5 p-6 text-center">
        <p className="font-medium text-success">
          All clear — no analysis issues detected.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <h2 className="flex items-center gap-2 text-lg font-medium">
        <AlertTriangle className="h-5 w-5 text-warning" aria-hidden />
        Analysis Alerts
      </h2>
      <div className="grid gap-3 sm:grid-cols-2">
        {alerts
          .filter((a) => a.count > 0)
          .map((alert) => (
            <button
              key={alert.label}
              type="button"
              className={cn(
                "flex items-center gap-4 rounded-lg border p-4 text-left transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                alert.accent,
              )}
              onClick={() => navigate(alert.path)}
            >
              <alert.icon className="h-8 w-8" aria-hidden />
              <div className="flex-1">
                <p className="text-sm font-medium text-foreground">
                  {alert.label}
                </p>
                <p className="text-2xl font-semibold">{alert.count}</p>
              </div>
              <ArrowRight
                className="h-4 w-4 text-muted-foreground"
                aria-hidden
              />
            </button>
          ))}
      </div>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-48" />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {["s1", "s2", "s3", "s4", "s5"].map((key) => (
          <Skeleton key={key} className="h-20 border border-border" />
        ))}
      </div>
    </div>
  );
}
