import { StatusBadge } from "@/components/ui/badge";
import { useImpact } from "@/hooks/use-analysis";
import type { RequirementSummaryResponse } from "@/types/api";
import { Link, useParams } from "react-router";

export function ImpactTab({ requirementId }: { requirementId: string }) {
  const { projectId } = useParams<{ projectId: string }>();
  const { data: impacted = [], isLoading } = useImpact(requirementId);

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  if (impacted.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No transitively impacted requirements.
      </p>
    );
  }

  return (
    <div className="space-y-2">
      <p className="text-sm text-muted-foreground mb-3">
        {impacted.length} requirement(s) would be impacted by changes to this
        requirement.
      </p>
      {impacted.map((r: RequirementSummaryResponse) => (
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
