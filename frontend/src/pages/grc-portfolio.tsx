import {
  AsOfDateControl,
  ScopeControlsShell,
} from "@/components/workspace-shared";
import {
  type GrcPortfolioData,
  type GrcPortfolioFilters,
  useGrcPortfolio,
} from "@/hooks/use-grc-portfolio";
import type {
  AssetCriticality,
  AssetEnvironment,
  AssetResponse,
  ControlWorkspaceQueueReason,
  EvidenceFreshnessCounts,
  EvidenceStateArtifact,
  FindingResponse,
  FindingSeverity,
  FindingStatus,
  ScenarioReviewState,
  WorkspaceAssessment,
} from "@/types/api";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";

const REVIEW_LABELS: Record<ScenarioReviewState, string> = {
  REASSESSMENT_REQUIRED: "Reassessment required",
  REVIEW_DUE: "Review due",
  EVIDENCE_STALE: "Evidence stale",
  CURRENT: "Current",
  NO_SIGNAL: "No signal",
};

const REVIEW_STATES: ScenarioReviewState[] = [
  "REASSESSMENT_REQUIRED",
  "REVIEW_DUE",
  "EVIDENCE_STALE",
  "CURRENT",
  "NO_SIGNAL",
];

const QUEUE_LABELS: Record<ControlWorkspaceQueueReason, string> = {
  OWNER_MISSING: "Owner missing",
  STATUS_DRAFT: "Draft",
  TEST_EVIDENCE_MISSING: "Test evidence missing",
  ASSESSMENT_MISSING: "Assessment missing",
  OPEN_EXCEPTION: "Open exception",
  EFFECTIVENESS_WEAK: "Weak effectiveness",
  CURRENT: "Current",
};

const QUEUE_REASONS: ControlWorkspaceQueueReason[] = [
  "OWNER_MISSING",
  "STATUS_DRAFT",
  "TEST_EVIDENCE_MISSING",
  "ASSESSMENT_MISSING",
  "OPEN_EXCEPTION",
  "EFFECTIVENESS_WEAK",
  "CURRENT",
];

const FINDING_STATUSES: FindingStatus[] = [
  "OPEN",
  "REMEDIATION_IN_PROGRESS",
  "REMEDIATION_COMPLETE",
  "VERIFIED_CLOSED",
];

const FINDING_SEVERITIES: FindingSeverity[] = [
  "CRITICAL",
  "HIGH",
  "MEDIUM",
  "LOW",
  "INFORMATIONAL",
];

const CRITICALITIES: AssetCriticality[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];

const ENVIRONMENTS: AssetEnvironment[] = [
  "PRODUCTION",
  "STAGING",
  "DEVELOPMENT",
  "TEST",
  "NON_PRODUCTION",
  "OTHER",
];

const METHODOLOGY_FAMILIES = ["FAIR", "NIST", "ISO", "Other"] as const;
type MethodologyFamily = (typeof METHODOLOGY_FAMILIES)[number];

function increment<K extends string>(
  counts: Partial<Record<K, number>>,
  key: K,
  by = 1,
) {
  counts[key] = (counts[key] ?? 0) + by;
}

function percentage(value: number, total: number): string {
  if (total === 0) return "0%";
  return `${Math.round((value / total) * 100)}%`;
}

function formatEnum(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function familyForAssessment(
  assessment: WorkspaceAssessment,
): MethodologyFamily {
  const name = assessment.methodologyProfileName?.toUpperCase() ?? "";
  if (name.includes("FAIR")) return "FAIR";
  if (name.includes("NIST")) return "NIST";
  if (name.includes("ISO")) return "ISO";
  return "Other";
}

function parseDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function isOpenFinding(finding: FindingResponse): boolean {
  return (
    finding.status === "OPEN" || finding.status === "REMEDIATION_IN_PROGRESS"
  );
}

function graphPath(projectId: string): string {
  return `/p/${projectId}/graph`;
}

function GraphLink({
  projectId,
  graphNodeId,
}: Readonly<{ projectId: string; graphNodeId: string }>) {
  return (
    <Link
      to={graphPath(projectId)}
      title={`Graph node ${graphNodeId}`}
      className="text-xs font-medium text-primary underline"
    >
      Open graph
    </Link>
  );
}

function MetricCard({
  label,
  value,
  detail,
  tone = "neutral",
}: Readonly<{
  label: string;
  value: string | number;
  detail?: string;
  tone?: "neutral" | "good" | "warn" | "bad" | "info";
}>) {
  const toneClass = {
    neutral: "text-foreground",
    good: "text-green-300",
    warn: "text-yellow-300",
    bad: "text-red-300",
    info: "text-blue-300",
  }[tone];

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs font-medium uppercase text-muted-foreground">
        {label}
      </p>
      <p className={`mt-2 text-2xl font-semibold ${toneClass}`}>{value}</p>
      {detail && <p className="mt-1 text-xs text-muted-foreground">{detail}</p>}
    </div>
  );
}

function SectionHeading({
  title,
  detail,
}: Readonly<{ title: string; detail?: string }>) {
  return (
    <div>
      <h2 className="text-lg font-semibold">{title}</h2>
      {detail && <p className="mt-1 text-sm text-muted-foreground">{detail}</p>}
    </div>
  );
}

function BarRow({
  label,
  count,
  total,
}: Readonly<{ label: string; count: number; total: number }>) {
  return (
    <div className="grid grid-cols-[9rem_1fr_3rem] items-center gap-3 text-sm">
      <span className="truncate text-muted-foreground">{label}</span>
      <div className="h-2 overflow-hidden rounded-full bg-muted">
        <div
          className="h-full rounded-full bg-primary"
          style={{ width: percentage(count, total) }}
        />
      </div>
      <span className="text-right font-medium">{count}</span>
    </div>
  );
}

function DetailRow({
  uid,
  title,
  graphNodeId,
  projectId,
  meta,
  to,
}: Readonly<{
  uid: string;
  title: string;
  graphNodeId: string;
  projectId: string;
  meta?: string;
  to?: string;
}>) {
  return (
    <li className="grid gap-2 border-b border-border py-2 last:border-0 md:grid-cols-[minmax(0,1fr)_auto]">
      <div className="min-w-0">
        <p className="truncate text-sm">
          <span className="font-mono font-semibold">{uid}</span>
          <span className="ml-2 text-muted-foreground">{title}</span>
        </p>
        <p className="mt-0.5 break-all font-mono text-xs text-muted-foreground">
          {graphNodeId}
        </p>
        {meta && <p className="mt-0.5 text-xs text-muted-foreground">{meta}</p>}
      </div>
      <div className="flex items-center gap-3">
        {to && (
          <Link to={to} className="text-xs font-medium text-primary underline">
            Open workspace
          </Link>
        )}
        <GraphLink projectId={projectId} graphNodeId={graphNodeId} />
      </div>
    </li>
  );
}

function ScopeControls({
  filters,
  onChange,
}: Readonly<{
  filters: GrcPortfolioFilters;
  onChange: (next: GrcPortfolioFilters) => void;
}>) {
  return (
    <ScopeControlsShell>
      <AsOfDateControl
        value={filters.asOf}
        onChange={(asOf) => onChange({ ...filters, asOf })}
      />
      <div>
        <label className="mb-1 block text-xs font-medium">
          Freshness window
        </label>
        <input
          aria-label="Freshness window"
          type="number"
          min={1}
          className="w-28 rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.freshnessWindowDays ?? 90}
          onChange={(event) =>
            onChange({
              ...filters,
              freshnessWindowDays: Number(event.target.value) || undefined,
            })
          }
        />
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={filters.includeSuperseded ?? false}
          onChange={(event) =>
            onChange({ ...filters, includeSuperseded: event.target.checked })
          }
        />
        Include superseded
      </label>
    </ScopeControlsShell>
  );
}

function RiskPostureSection({
  data,
  projectId,
}: Readonly<{ data: GrcPortfolioData; projectId: string }>) {
  const reviewCounts: Partial<Record<ScenarioReviewState, number>> = {};
  const statusCounts: Record<string, number> = {};
  const treatmentCounts: Record<string, number> = {};

  for (const scenario of data.risk.scenarios) {
    increment(reviewCounts, scenario.reviewIndicator);
    increment(statusCounts, scenario.status);
    for (const treatment of scenario.treatments) {
      increment(treatmentCounts, treatment.status);
    }
  }

  const priorityScenarios = [...data.risk.scenarios]
    .sort(
      (a, b) =>
        REVIEW_STATES.indexOf(a.reviewIndicator) -
        REVIEW_STATES.indexOf(b.reviewIndicator),
    )
    .slice(0, 5);

  return (
    <section className="space-y-3">
      <SectionHeading
        title="Risk posture"
        detail={`${data.risk.scenarioCount} scenarios across ${data.risk.assetCount} scoped assets.`}
      />
      <div className="grid gap-3 md:grid-cols-4">
        <MetricCard
          label="Reassessment required"
          value={reviewCounts.REASSESSMENT_REQUIRED ?? 0}
          detail="Highest review indicator"
          tone={(reviewCounts.REASSESSMENT_REQUIRED ?? 0) > 0 ? "bad" : "good"}
        />
        <MetricCard
          label="Review due"
          value={reviewCounts.REVIEW_DUE ?? 0}
          tone={(reviewCounts.REVIEW_DUE ?? 0) > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Evidence stale"
          value={reviewCounts.EVIDENCE_STALE ?? 0}
          tone={(reviewCounts.EVIDENCE_STALE ?? 0) > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Current"
          value={reviewCounts.CURRENT ?? 0}
          tone="good"
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Scenario review mix</p>
          {REVIEW_STATES.map((state) => (
            <BarRow
              key={state}
              label={REVIEW_LABELS[state]}
              count={reviewCounts[state] ?? 0}
              total={data.risk.scenarioCount}
            />
          ))}
        </div>
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Scenario and treatment states</p>
          {Object.entries(statusCounts).map(([status, count]) => (
            <BarRow
              key={status}
              label={formatEnum(status)}
              count={count}
              total={data.risk.scenarioCount}
            />
          ))}
          {Object.entries(treatmentCounts).map(([status, count]) => (
            <BarRow
              key={`treatment-${status}`}
              label={`Treatment ${formatEnum(status)}`}
              count={count}
              total={Math.max(
                1,
                Object.values(treatmentCounts).reduce((a, b) => a + b, 0),
              )}
            />
          ))}
        </div>
      </div>
      <ul className="rounded-lg border border-border bg-card px-4">
        {priorityScenarios.map((scenario) => (
          <DetailRow
            key={scenario.id}
            uid={scenario.uid}
            title={scenario.title}
            graphNodeId={`RISK_SCENARIO:${scenario.id}`}
            projectId={projectId}
            meta={`${REVIEW_LABELS[scenario.reviewIndicator]} / ${scenario.status}`}
            to={`/p/${projectId}/risk-scenarios`}
          />
        ))}
      </ul>
    </section>
  );
}

function ControlHealthSection({
  data,
  projectId,
}: Readonly<{ data: GrcPortfolioData; projectId: string }>) {
  const queueCounts: Partial<Record<ControlWorkspaceQueueReason, number>> = {};
  const operatingEffectiveness: Record<string, number> = {};

  for (const control of data.controls.controls) {
    for (const reason of control.queueReasons) {
      increment(queueCounts, reason);
    }
    for (const assessment of control.assessments) {
      increment(operatingEffectiveness, assessment.operatingEffectiveness);
    }
  }

  const controlsWithExceptions = data.controls.controls
    .filter((control) => control.queueReasons.includes("OPEN_EXCEPTION"))
    .slice(0, 5);

  return (
    <section className="space-y-3">
      <SectionHeading
        title="Control health"
        detail={`${data.controls.controlCount} controls with assurance queues and effectiveness ratings.`}
      />
      <div className="grid gap-3 md:grid-cols-4">
        <MetricCard
          label="Controls"
          value={data.controls.controlCount}
          tone="info"
        />
        <MetricCard
          label="Open exception"
          value={queueCounts.OPEN_EXCEPTION ?? 0}
          tone={(queueCounts.OPEN_EXCEPTION ?? 0) > 0 ? "bad" : "good"}
        />
        <MetricCard
          label="Missing assessment"
          value={queueCounts.ASSESSMENT_MISSING ?? 0}
          tone={(queueCounts.ASSESSMENT_MISSING ?? 0) > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Current"
          value={queueCounts.CURRENT ?? 0}
          tone="good"
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Owner queue</p>
          {QUEUE_REASONS.map((reason) => (
            <BarRow
              key={reason}
              label={QUEUE_LABELS[reason]}
              count={queueCounts[reason] ?? 0}
              total={data.controls.controlCount}
            />
          ))}
        </div>
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Operating effectiveness</p>
          {Object.entries(operatingEffectiveness).map(([rating, count]) => (
            <BarRow
              key={rating}
              label={formatEnum(rating)}
              count={count}
              total={Object.values(operatingEffectiveness).reduce(
                (a, b) => a + b,
                0,
              )}
            />
          ))}
        </div>
      </div>
      <ul className="rounded-lg border border-border bg-card px-4">
        {controlsWithExceptions.map((control) => (
          <DetailRow
            key={control.id}
            uid={control.uid}
            title={control.title}
            graphNodeId={`CONTROL:${control.id}`}
            projectId={projectId}
            meta={`${control.status} / ${control.controlFunction}`}
            to={`/p/${projectId}/control-assurance`}
          />
        ))}
      </ul>
    </section>
  );
}

function EvidenceFreshnessSection({
  data,
  projectId,
}: Readonly<{ data: GrcPortfolioData; projectId: string }>) {
  const counts: EvidenceFreshnessCounts = data.evidence.counts;
  const staleArtifacts = data.evidence.evidenceArtifacts
    .filter((artifact) =>
      ["STALE", "EXPIRED", "SUPERSEDED"].includes(artifact.freshnessState),
    )
    .slice(0, 5);

  return (
    <section className="space-y-3">
      <SectionHeading
        title="Evidence freshness"
        detail={`${data.evidence.artifactCount} artifacts and ${data.evidence.observationCount} observations.`}
      />
      <div className="grid gap-3 md:grid-cols-5">
        <MetricCard label="Fresh" value={counts.fresh} tone="good" />
        <MetricCard
          label="Stale"
          value={counts.stale}
          tone={counts.stale > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Expired"
          value={counts.expired}
          tone={counts.expired > 0 ? "bad" : "good"}
        />
        <MetricCard label="Superseded" value={counts.superseded} />
        <MetricCard
          label="Currently valid"
          value={counts.currentlyValid}
          tone="info"
        />
      </div>
      <ul className="rounded-lg border border-border bg-card px-4">
        {staleArtifacts.length === 0 ? (
          <li className="py-3 text-sm text-muted-foreground">
            No stale or expired evidence artifacts.
          </li>
        ) : (
          staleArtifacts.map((artifact: EvidenceStateArtifact) => (
            <DetailRow
              key={artifact.id}
              uid={artifact.uid}
              title={artifact.title}
              graphNodeId={`EVIDENCE_ARTIFACT:${artifact.id}`}
              projectId={projectId}
              meta={`${formatEnum(artifact.freshnessState)} / ${artifact.ageDays} days old`}
              to={`/p/${projectId}/evidence-state`}
            />
          ))
        )}
      </ul>
    </section>
  );
}

function FindingTrendsSection({
  data,
  projectId,
}: Readonly<{ data: GrcPortfolioData; projectId: string }>) {
  const statusCounts: Partial<Record<FindingStatus, number>> = {};
  const severityCounts: Partial<Record<FindingSeverity, number>> = {};
  const now = new Date();
  const thirtyDaysAgo = new Date(now);
  thirtyDaysAgo.setDate(now.getDate() - 30);
  const thirtyDaysAhead = new Date(now);
  thirtyDaysAhead.setDate(now.getDate() + 30);

  let newThisMonth = 0;
  let overdue = 0;
  let dueSoon = 0;

  for (const finding of data.findings) {
    increment(statusCounts, finding.status);
    increment(severityCounts, finding.severity);

    const createdAt = parseDate(finding.createdAt);
    if (createdAt && createdAt >= thirtyDaysAgo) {
      newThisMonth += 1;
    }

    const dueDate = parseDate(finding.dueDate);
    if (dueDate && isOpenFinding(finding)) {
      if (dueDate < now) overdue += 1;
      if (dueDate >= now && dueDate <= thirtyDaysAhead) dueSoon += 1;
    }
  }

  const recentFindings = [...data.findings]
    .sort(
      (a, b) =>
        (parseDate(b.updatedAt)?.getTime() ?? 0) -
        (parseDate(a.updatedAt)?.getTime() ?? 0),
    )
    .slice(0, 5);

  return (
    <section className="space-y-3">
      <SectionHeading
        title="Finding trends"
        detail={`${data.findings.length} findings across remediation states and severities.`}
      />
      <div className="grid gap-3 md:grid-cols-4">
        <MetricCard label="New 30 days" value={newThisMonth} tone="info" />
        <MetricCard
          label="Overdue"
          value={overdue}
          tone={overdue > 0 ? "bad" : "good"}
        />
        <MetricCard
          label="Due 30 days"
          value={dueSoon}
          tone={dueSoon > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Open"
          value={
            (statusCounts.OPEN ?? 0) +
            (statusCounts.REMEDIATION_IN_PROGRESS ?? 0)
          }
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Status</p>
          {FINDING_STATUSES.map((status) => (
            <BarRow
              key={status}
              label={formatEnum(status)}
              count={statusCounts[status] ?? 0}
              total={data.findings.length}
            />
          ))}
        </div>
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Severity</p>
          {FINDING_SEVERITIES.map((severity) => (
            <BarRow
              key={severity}
              label={formatEnum(severity)}
              count={severityCounts[severity] ?? 0}
              total={data.findings.length}
            />
          ))}
        </div>
      </div>
      <ul className="rounded-lg border border-border bg-card px-4">
        {recentFindings.map((finding) => (
          <DetailRow
            key={finding.id}
            uid={finding.uid}
            title={finding.title}
            graphNodeId={finding.graphNodeId}
            projectId={projectId}
            meta={`${formatEnum(finding.severity)} / ${formatEnum(finding.status)}`}
            to={graphPath(projectId)}
          />
        ))}
      </ul>
    </section>
  );
}

function AssetCriticalitySection({
  data,
  projectId,
}: Readonly<{ data: GrcPortfolioData; projectId: string }>) {
  const criticalityCounts: Partial<Record<AssetCriticality, number>> = {};
  const environmentCounts: Partial<Record<AssetEnvironment, number>> = {};

  for (const asset of data.assets) {
    increment(criticalityCounts, asset.criticality);
    increment(environmentCounts, asset.environment);
  }

  const highCriticalProduction = data.assets.filter(
    (asset) =>
      asset.environment === "PRODUCTION" &&
      (asset.criticality === "CRITICAL" || asset.criticality === "HIGH"),
  );

  return (
    <section className="space-y-3">
      <SectionHeading
        title="Asset criticality"
        detail={`${data.assets.length} operational assets by criticality, environment, and scope.`}
      />
      <div className="grid gap-3 md:grid-cols-4">
        <MetricCard
          label="Critical / high production"
          value={highCriticalProduction.length}
          detail={percentage(highCriticalProduction.length, data.assets.length)}
          tone={highCriticalProduction.length > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Critical"
          value={criticalityCounts.CRITICAL ?? 0}
          tone={(criticalityCounts.CRITICAL ?? 0) > 0 ? "bad" : "good"}
        />
        <MetricCard
          label="High"
          value={criticalityCounts.HIGH ?? 0}
          tone={(criticalityCounts.HIGH ?? 0) > 0 ? "warn" : "good"}
        />
        <MetricCard
          label="Production"
          value={environmentCounts.PRODUCTION ?? 0}
          tone="info"
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Criticality concentration</p>
          {CRITICALITIES.map((criticality) => (
            <BarRow
              key={criticality}
              label={formatEnum(criticality)}
              count={criticalityCounts[criticality] ?? 0}
              total={data.assets.length}
            />
          ))}
        </div>
        <div className="space-y-2 rounded-lg border border-border bg-card p-4">
          <p className="text-sm font-medium">Environment concentration</p>
          {ENVIRONMENTS.map((environment) => (
            <BarRow
              key={environment}
              label={formatEnum(environment)}
              count={environmentCounts[environment] ?? 0}
              total={data.assets.length}
            />
          ))}
        </div>
      </div>
      <ul className="rounded-lg border border-border bg-card px-4">
        {highCriticalProduction.slice(0, 5).map((asset: AssetResponse) => (
          <DetailRow
            key={asset.id}
            uid={asset.uid}
            title={asset.name}
            graphNodeId={asset.graphNodeId}
            projectId={projectId}
            meta={`${asset.criticality} / ${asset.environment} / ${asset.scopeDesignation}`}
            to={graphPath(projectId)}
          />
        ))}
      </ul>
    </section>
  );
}

function MethodologySection({ data }: Readonly<{ data: GrcPortfolioData }>) {
  const familyCounts: Record<MethodologyFamily, number> = {
    FAIR: 0,
    NIST: 0,
    ISO: 0,
    Other: 0,
  };
  const familyOutputs: Record<MethodologyFamily, number> = {
    FAIR: 0,
    NIST: 0,
    ISO: 0,
    Other: 0,
  };
  const familyApproved: Record<MethodologyFamily, number> = {
    FAIR: 0,
    NIST: 0,
    ISO: 0,
    Other: 0,
  };

  for (const scenario of data.risk.scenarios) {
    for (const assessment of scenario.assessments) {
      const family = familyForAssessment(assessment);
      familyCounts[family] += 1;
      if (assessment.hasComputedOutputs) familyOutputs[family] += 1;
      if (assessment.approvalState === "APPROVED") familyApproved[family] += 1;
    }
  }

  return (
    <section className="space-y-3">
      <SectionHeading
        title="Methodology summaries"
        detail="Assessment coverage by FAIR, NIST, ISO, and other methodology profiles."
      />
      <div className="grid gap-3 md:grid-cols-4">
        {METHODOLOGY_FAMILIES.map((family) => (
          <MetricCard
            key={family}
            label={family}
            value={familyCounts[family]}
            detail={`${familyOutputs[family]} with outputs / ${familyApproved[family]} approved`}
            tone={familyCounts[family] > 0 ? "info" : "neutral"}
          />
        ))}
      </div>
    </section>
  );
}

function hasPortfolioData(data: GrcPortfolioData): boolean {
  return (
    data.risk.scenarioCount > 0 ||
    data.controls.controlCount > 0 ||
    data.evidence.artifactCount > 0 ||
    data.evidence.observationCount > 0 ||
    data.findings.length > 0 ||
    data.assets.length > 0
  );
}

export function GrcPortfolio() {
  const [filters, setFilters] = useState<GrcPortfolioFilters>({});
  const { projectId = "" } = useParams<{ projectId: string }>();
  const { data, isLoading, isError, error } = useGrcPortfolio(filters);

  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground">
        Loading portfolio...
      </div>
    );
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-destructive bg-card p-6 text-sm text-destructive">
        {error instanceof Error ? error.message : "Unable to load portfolio."}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">GRC Portfolio</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Project-level posture across risk, controls, evidence, findings,
            assets, and methodologies.
          </p>
        </div>
        <ScopeControls filters={filters} onChange={setFilters} />
      </div>

      {!data || !hasPortfolioData(data) ? (
        <div className="rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground">
          No portfolio data matches the current scope.
        </div>
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-5">
            <MetricCard
              label="Scenarios"
              value={data.risk.scenarioCount}
              tone="info"
            />
            <MetricCard
              label="Controls"
              value={data.controls.controlCount}
              tone="info"
            />
            <MetricCard
              label="Evidence"
              value={data.evidence.artifactCount}
              tone="info"
            />
            <MetricCard
              label="Findings"
              value={data.findings.length}
              tone={data.findings.some(isOpenFinding) ? "warn" : "good"}
            />
            <MetricCard label="Assets" value={data.assets.length} tone="info" />
          </div>

          <RiskPostureSection data={data} projectId={projectId} />
          <ControlHealthSection data={data} projectId={projectId} />
          <EvidenceFreshnessSection data={data} projectId={projectId} />
          <FindingTrendsSection data={data} projectId={projectId} />
          <AssetCriticalitySection data={data} projectId={projectId} />
          <MethodologySection data={data} />
        </>
      )}
    </div>
  );
}
