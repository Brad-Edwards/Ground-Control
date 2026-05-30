import {
  type PortfolioSummaryFilters,
  usePortfolioSummary,
} from "@/hooks/use-portfolio-summary";
import type { Distribution, PortfolioMethodologySummary } from "@/types/api";
import { useState } from "react";

// ── Shared primitives ─────────────────────────────────────────────────────────

function StatCard({
  label,
  value,
  emphasis,
}: {
  label: string;
  value: number;
  emphasis?: boolean;
}) {
  return (
    <div className="rounded-lg border border-border bg-card p-3 text-center">
      <div
        className={`text-2xl font-semibold ${emphasis && value > 0 ? "text-destructive" : ""}`}
      >
        {value}
      </div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  );
}

function DrillChips({ label, uids }: { label: string; uids: string[] }) {
  if (uids.length === 0) return null;
  return (
    <div className="mt-1 text-xs">
      <span className="font-medium text-muted-foreground">{label}: </span>
      {uids.map((uid, i) => (
        <span key={uid}>
          {i > 0 && ", "}
          <span className="font-mono">{uid}</span>
        </span>
      ))}
    </div>
  );
}

function DistributionList({
  title,
  distribution,
}: {
  title: string;
  distribution: Distribution;
}) {
  const entries = Object.entries(distribution).sort((a, b) => b[1] - a[1]);
  const total = entries.reduce((sum, [, n]) => sum + n, 0);
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <p className="mb-2 text-sm font-medium">{title}</p>
      {entries.length === 0 ? (
        <p className="text-xs text-muted-foreground">No data</p>
      ) : (
        <ul className="space-y-1">
          {entries.map(([key, count]) => (
            <li key={key} className="text-xs">
              <div className="mb-0.5 flex justify-between">
                <span className="font-mono">{key}</span>
                <span className="text-muted-foreground">{count}</span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded bg-muted">
                <div
                  className="h-full bg-primary"
                  style={{
                    width: `${total === 0 ? 0 : Math.round((count / total) * 100)}%`,
                  }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Section({
  id,
  title,
  children,
}: {
  id: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-labelledby={`${id}-heading`}>
      <h2 id={`${id}-heading`} className="mb-2 text-lg font-medium">
        {title}
      </h2>
      {children}
    </section>
  );
}

// ── Methodology table ──────────────────────────────────────────────────────────

function MethodologyTable({
  summaries,
}: {
  summaries: PortfolioMethodologySummary[];
}) {
  if (summaries.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No methodology profiles in scope.
      </p>
    );
  }
  return (
    <div className="overflow-auto rounded-lg border border-border">
      <table className="w-full text-left text-sm">
        <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
          <tr>
            <th className="px-3 py-2">Family</th>
            <th className="px-3 py-2">Profiles</th>
            <th className="px-3 py-2">Assessments</th>
            <th className="px-3 py-2">Approved</th>
            <th className="px-3 py-2">With outputs</th>
          </tr>
        </thead>
        <tbody>
          {summaries.map((s) => (
            <tr key={s.family} className="border-b border-border last:border-0">
              <td className="px-3 py-2 font-mono">{s.family}</td>
              <td className="px-3 py-2">{s.profileCount}</td>
              <td className="px-3 py-2">{s.assessmentCount}</td>
              <td className="px-3 py-2">{s.approvedAssessmentCount}</td>
              <td className="px-3 py-2">{s.assessmentsWithComputedOutputs}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ── Scope controls ────────────────────────────────────────────────────────────

function ScopeControls({
  filters,
  onChange,
}: {
  filters: PortfolioSummaryFilters;
  onChange: (f: PortfolioSummaryFilters) => void;
}) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
      <div>
        <label className="mb-1 block text-xs font-medium" htmlFor="pf-asof">
          As of (ISO date)
        </label>
        <input
          id="pf-asof"
          type="datetime-local"
          className="rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.asOf?.slice(0, 16) ?? ""}
          onChange={(e) =>
            onChange({
              ...filters,
              asOf: e.target.value ? `${e.target.value}:00Z` : undefined,
            })
          }
        />
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function PortfolioViews() {
  const [filters, setFilters] = useState<PortfolioSummaryFilters>({});
  const { data, isLoading, isError, error } = usePortfolioSummary(filters);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">GRC Portfolio</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Portfolio views over risk posture, control health, evidence freshness,
          finding trends, asset criticality concentration, and methodology
          summaries. Drill-down identifiers are surfaced per dimension.
        </p>
      </div>

      <ScopeControls filters={filters} onChange={setFilters} />

      {isLoading && (
        <div className="flex min-h-[20vh] items-center justify-center text-muted-foreground">
          Loading portfolio&hellip;
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to load portfolio."}
        </div>
      )}

      {data && (
        <>
          <Section id="risk-posture" title="Risk Posture">
            <div className="mb-3 grid grid-cols-2 gap-3 sm:grid-cols-5">
              <StatCard
                label="Scenarios"
                value={data.riskPosture.totalScenarios}
              />
              <StatCard
                label="Assessments"
                value={data.riskPosture.totalAssessments}
              />
              <StatCard
                label="Treatments"
                value={data.riskPosture.totalTreatments}
              />
              <StatCard
                label="Reassessment signals"
                value={data.riskPosture.reassessmentSignals}
                emphasis
              />
              <StatCard
                label="Overdue reviews"
                value={data.riskPosture.overdueReviews}
                emphasis
              />
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <DistributionList
                title="Scenarios by status"
                distribution={data.riskPosture.scenariosByStatus}
              />
              <DistributionList
                title="Assessments by approval"
                distribution={data.riskPosture.assessmentsByApprovalState}
              />
              <DistributionList
                title="Treatments by status"
                distribution={data.riskPosture.treatmentsByStatus}
              />
            </div>
            <DrillChips
              label="Overdue reviews"
              uids={data.riskPosture.overdueRegisterRecordUids}
            />
          </Section>

          <Section id="control-health" title="Control Health">
            <div className="mb-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <StatCard
                label="Controls"
                value={data.controlHealth.totalControls}
              />
              <StatCard
                label="Unassessed"
                value={data.controlHealth.unassessedControls}
                emphasis
              />
              <StatCard
                label="Unmapped"
                value={data.controlHealth.unmappedControls}
                emphasis
              />
              <StatCard
                label="Currently valid evidence"
                value={data.evidenceFreshness.currentlyValid}
              />
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <DistributionList
                title="Controls by status"
                distribution={data.controlHealth.controlsByStatus}
              />
              <DistributionList
                title="Design effectiveness"
                distribution={
                  data.controlHealth.designEffectivenessDistribution
                }
              />
              <DistributionList
                title="Operating effectiveness"
                distribution={
                  data.controlHealth.operatingEffectivenessDistribution
                }
              />
            </div>
            <DrillChips
              label="Unassessed controls"
              uids={data.controlHealth.unassessedControlUids}
            />
            <DrillChips
              label="Unmapped controls"
              uids={data.controlHealth.unmappedControlUids}
            />
          </Section>

          <Section id="evidence-freshness" title="Evidence Freshness">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
              <StatCard label="Fresh" value={data.evidenceFreshness.fresh} />
              <StatCard
                label="Stale"
                value={data.evidenceFreshness.stale}
                emphasis
              />
              <StatCard
                label="Expired"
                value={data.evidenceFreshness.expired}
                emphasis
              />
              <StatCard
                label="Superseded"
                value={data.evidenceFreshness.superseded}
              />
              <StatCard
                label="Currently valid"
                value={data.evidenceFreshness.currentlyValid}
              />
            </div>
          </Section>

          <Section id="finding-trends" title="Finding Trends">
            <div className="mb-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
              <StatCard
                label="Findings"
                value={data.findingTrends.totalFindings}
              />
              <StatCard label="Open" value={data.findingTrends.openCount} />
              <StatCard
                label="Overdue"
                value={data.findingTrends.overdueCount}
                emphasis
              />
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <DistributionList
                title="By severity"
                distribution={data.findingTrends.bySeverity}
              />
              <DistributionList
                title="By status"
                distribution={data.findingTrends.byStatus}
              />
              <DistributionList
                title="By type"
                distribution={data.findingTrends.byType}
              />
            </div>
            <DrillChips
              label="Open findings"
              uids={data.findingTrends.openFindingUids}
            />
            <DrillChips
              label="Overdue findings"
              uids={data.findingTrends.overdueFindingUids}
            />
          </Section>

          <Section
            id="asset-criticality"
            title="Asset Criticality Concentration"
          >
            <div className="mb-3">
              <StatCard
                label="Assets"
                value={data.assetCriticality.totalAssets}
              />
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <DistributionList
                title="By criticality"
                distribution={data.assetCriticality.byCriticality}
              />
              <DistributionList
                title="By environment"
                distribution={data.assetCriticality.byEnvironment}
              />
              <DistributionList
                title="By scope"
                distribution={data.assetCriticality.byScope}
              />
            </div>
            <DrillChips
              label="Critical assets"
              uids={data.assetCriticality.criticalAssetUids}
            />
          </Section>

          <Section id="methodology" title="Methodology Summaries">
            <MethodologyTable summaries={data.methodologySummaries} />
          </Section>
        </>
      )}
    </div>
  );
}
