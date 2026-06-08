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
import { useNavigate, useParams } from "react-router-dom";

export function Dashboard() {
  const { activeProject, isLoading } = useProjectContext();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();

  if (isLoading) return <LoadingSkeleton />;

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <Rocket className="h-12 w-12 text-muted-foreground" />
        <h1 className="text-2xl font-semibold">Welcome to Ground Control</h1>
        <p className="text-muted-foreground">
          Select a project from the header to get started.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{activeProject.name}</h1>
        {activeProject.description && (
          <p className="mt-1 text-muted-foreground">
            {activeProject.description}
          </p>
        )}
      </div>

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
  const statCards = [
    {
      label: "Total",
      value: stats.totalRequirements,
      color: "text-foreground",
      filter: undefined,
    },
    {
      label: "Draft",
      value: stats.byStatus.DRAFT ?? 0,
      color: "text-gray-400",
      filter: "DRAFT",
    },
    {
      label: "Active",
      value: stats.byStatus.ACTIVE ?? 0,
      color: "text-green-400",
      filter: "ACTIVE",
    },
    {
      label: "Deprecated",
      value: stats.byStatus.DEPRECATED ?? 0,
      color: "text-orange-400",
      filter: "DEPRECATED",
    },
    {
      label: "Archived",
      value: stats.byStatus.ARCHIVED ?? 0,
      color: "text-gray-500",
      filter: "ARCHIVED",
    },
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
      {statCards.map((s) => (
        <button
          key={s.label}
          type="button"
          className="rounded-lg border border-border bg-card p-4 text-left transition-colors hover:bg-accent/30"
          onClick={() =>
            navigate(
              s.filter
                ? `/p/${projectId}/requirements?status=${s.filter}`
                : `/p/${projectId}/requirements`,
            )
          }
        >
          <p className="text-sm text-muted-foreground">{s.label}</p>
          <p className={cn("mt-1 text-2xl font-semibold", s.color)}>
            {s.value}
          </p>
        </button>
      ))}
    </div>
  );
}

const STATUS_BAR_COLORS: Record<string, string> = {
  DRAFT: "bg-gray-400",
  ACTIVE: "bg-green-400",
  DEPRECATED: "bg-orange-400",
  ARCHIVED: "bg-gray-500",
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
            className="flex w-full items-center gap-4 rounded-lg border border-border bg-card p-3 text-left transition-colors hover:bg-accent/30"
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
                    STATUS_BAR_COLORS[status] ?? "bg-blue-400",
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
            className="flex w-full items-center gap-4 rounded-lg border border-border bg-card p-3 text-left transition-colors hover:bg-accent/30"
            onClick={() => navigate(`/p/${projectId}/analysis`)}
          >
            <span className="w-28 shrink-0 text-sm font-medium text-muted-foreground">
              {linkType}
            </span>
            <div className="flex h-4 flex-1 overflow-hidden rounded-full bg-muted">
              <div
                className={cn(
                  "h-full rounded-full",
                  cov.percentage >= 80
                    ? "bg-green-400"
                    : cov.percentage >= 50
                      ? "bg-yellow-400"
                      : "bg-red-400",
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

const REVISION_TYPE_COLORS: Record<string, string> = {
  ADD: "bg-green-500/20 text-green-400",
  MOD: "bg-blue-500/20 text-blue-400",
  DEL: "bg-red-500/20 text-red-400",
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
      <h2 className="text-lg font-medium flex items-center gap-2">
        <Clock className="h-5 w-5 text-muted-foreground" />
        Recent Changes
      </h2>
      <div className="space-y-1">
        {changes.map((change, idx) => (
          <button
            key={`${change.uid}-${idx}`}
            type="button"
            className="flex w-full items-center gap-3 rounded-lg border border-border bg-card px-3 py-2 text-left transition-colors hover:bg-accent/30"
            onClick={() =>
              navigate(
                `/p/${projectId}/requirements?search=${encodeURIComponent(change.uid)}`,
              )
            }
          >
            <span
              className={cn(
                "inline-flex shrink-0 items-center rounded px-1.5 py-0.5 text-xs font-medium",
                REVISION_TYPE_COLORS[change.revisionType] ??
                  "bg-muted text-muted-foreground",
              )}
            >
              {change.revisionType}
            </span>
            <span className="shrink-0 text-sm font-mono text-muted-foreground">
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

  const alerts = [
    {
      icon: GitFork,
      label: "Dependency Cycles",
      count: cycles?.length ?? 0,
      color: "text-red-400",
      bgColor: "border-red-500/20 bg-red-500/5",
      path: `/p/${projectId}/analysis`,
    },
    {
      icon: Unlink,
      label: "Orphan Requirements",
      count: orphans?.length ?? 0,
      color: "text-yellow-400",
      bgColor: "border-yellow-500/20 bg-yellow-500/5",
      path: `/p/${projectId}/analysis`,
    },
    {
      icon: Link2Off,
      label: "Missing IMPLEMENTS Links",
      count: coverageGaps?.length ?? 0,
      color: "text-orange-400",
      bgColor: "border-orange-500/20 bg-orange-500/5",
      path: `/p/${projectId}/analysis`,
    },
    {
      icon: Layers,
      label: "Cross-Wave Violations",
      count: crossWave?.length ?? 0,
      color: "text-violet-400",
      bgColor: "border-violet-500/20 bg-violet-500/5",
      path: `/p/${projectId}/analysis`,
    },
  ];

  const hasAlerts = alerts.some((a) => a.count > 0);

  if (!hasAlerts) {
    return (
      <div className="rounded-lg border border-green-500/20 bg-green-500/5 p-6 text-center">
        <p className="text-green-400 font-medium">
          All clear — no analysis issues detected.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-medium flex items-center gap-2">
        <AlertTriangle className="h-5 w-5 text-yellow-400" />
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
                "flex items-center gap-4 rounded-lg border p-4 text-left transition-colors hover:bg-accent/30",
                alert.bgColor,
              )}
              onClick={() => navigate(alert.path)}
            >
              <alert.icon className={cn("h-8 w-8", alert.color)} />
              <div className="flex-1">
                <p className="text-sm font-medium">{alert.label}</p>
                <p className={cn("text-2xl font-semibold", alert.color)}>
                  {alert.count}
                </p>
              </div>
              <ArrowRight className="h-4 w-4 text-muted-foreground" />
            </button>
          ))}
      </div>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="space-y-6">
      <div className="h-8 w-48 animate-pulse rounded bg-muted" />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {["s1", "s2", "s3", "s4", "s5"].map((key) => (
          <div
            key={key}
            className="h-20 animate-pulse rounded-lg border border-border bg-card"
          />
        ))}
      </div>
    </div>
  );
}
