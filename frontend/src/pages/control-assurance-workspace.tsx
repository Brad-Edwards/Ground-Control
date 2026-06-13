import {
  AsOfDateControl,
  IndicatorBadge,
  ScopeControlsShell,
  WorkspaceShell,
  WorkspaceStatusSelect,
} from "@/components/workspace-shared";
import {
  type ControlAssuranceWorkspaceFilters,
  useControlAssuranceWorkspace,
} from "@/hooks/use-control-assurance-workspace";
import type {
  ControlFunction,
  ControlStatus,
  ControlWorkspaceControl,
  ControlWorkspaceQueueReason,
} from "@/types/api";
import { CONTROL_FUNCTIONS, CONTROL_STATUSES } from "@/types/api";
import { type ReactNode, useState } from "react";

const QUEUE_VALUES: ControlWorkspaceQueueReason[] = [
  "OWNER_MISSING",
  "STATUS_DRAFT",
  "TEST_EVIDENCE_MISSING",
  "ASSESSMENT_MISSING",
  "OPEN_EXCEPTION",
  "EFFECTIVENESS_WEAK",
  "CURRENT",
];

const QUEUE_STYLE_MAP: Record<ControlWorkspaceQueueReason, string> = {
  OWNER_MISSING: "bg-yellow-100 text-yellow-800",
  STATUS_DRAFT: "bg-slate-100 text-slate-700",
  TEST_EVIDENCE_MISSING: "bg-orange-100 text-orange-800",
  ASSESSMENT_MISSING: "bg-blue-100 text-blue-800",
  OPEN_EXCEPTION: "bg-red-100 text-red-800",
  EFFECTIVENESS_WEAK: "bg-purple-100 text-purple-800",
  CURRENT: "bg-green-100 text-green-800",
};

const QUEUE_LABEL_MAP: Record<ControlWorkspaceQueueReason, string> = {
  OWNER_MISSING: "Owner missing",
  STATUS_DRAFT: "Draft",
  TEST_EVIDENCE_MISSING: "Test evidence missing",
  ASSESSMENT_MISSING: "Assessment missing",
  OPEN_EXCEPTION: "Open exception",
  EFFECTIVENESS_WEAK: "Weak effectiveness",
  CURRENT: "Current",
};

function QueueBadge({
  reason,
}: Readonly<{ reason: ControlWorkspaceQueueReason }>) {
  return (
    <IndicatorBadge
      state={reason}
      styleMap={QUEUE_STYLE_MAP}
      labelMap={QUEUE_LABEL_MAP}
      ariaPrefix="Control queue"
    />
  );
}

function MetadataRow({
  label,
  value,
}: Readonly<{ label: string; value: string | number | null | undefined }>) {
  if (value == null || value === "") {
    return null;
  }
  return (
    <div>
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="break-words text-sm">{value}</dd>
    </div>
  );
}

function ControlFunctionSelect({
  value,
  onChange,
}: Readonly<{
  value: ControlFunction | undefined;
  onChange: (value: ControlFunction | undefined) => void;
}>) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium">Function</label>
      <select
        aria-label="Function"
        className="rounded border border-border bg-background px-2 py-1 text-sm"
        value={value ?? ""}
        onChange={(event) =>
          onChange((event.target.value as ControlFunction) || undefined)
        }
      >
        <option value="">All</option>
        {CONTROL_FUNCTIONS.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </div>
  );
}

function QueueSelect({
  value,
  onChange,
}: Readonly<{
  value: ControlWorkspaceQueueReason | undefined;
  onChange: (value: ControlWorkspaceQueueReason | undefined) => void;
}>) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium">Queue</label>
      <select
        aria-label="Queue"
        className="rounded border border-border bg-background px-2 py-1 text-sm"
        value={value ?? ""}
        onChange={(event) =>
          onChange(
            (event.target.value as ControlWorkspaceQueueReason) || undefined,
          )
        }
      >
        <option value="">All</option>
        {QUEUE_VALUES.map((option) => (
          <option key={option} value={option}>
            {QUEUE_LABEL_MAP[option]}
          </option>
        ))}
      </select>
    </div>
  );
}

function ScopeControls({
  filters,
  onChange,
}: Readonly<{
  filters: ControlAssuranceWorkspaceFilters;
  onChange: (next: ControlAssuranceWorkspaceFilters) => void;
}>) {
  return (
    <ScopeControlsShell>
      <WorkspaceStatusSelect<ControlStatus>
        value={filters.status}
        options={CONTROL_STATUSES}
        onChange={(status) => onChange({ ...filters, status })}
      />
      <ControlFunctionSelect
        value={filters.controlFunction}
        onChange={(controlFunction) =>
          onChange({ ...filters, controlFunction })
        }
      />
      <QueueSelect
        value={filters.queue}
        onChange={(queue) => onChange({ ...filters, queue })}
      />
      <div>
        <label className="mb-1 block text-xs font-medium">Owner</label>
        <input
          aria-label="Owner"
          type="search"
          className="w-36 rounded border border-border bg-background px-2 py-1 text-sm"
          value={filters.owner ?? ""}
          onChange={(event) =>
            onChange({ ...filters, owner: event.target.value || undefined })
          }
        />
      </div>
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
    </ScopeControlsShell>
  );
}

function DetailList({
  heading,
  children,
}: Readonly<{ heading: string; children: ReactNode }>) {
  return (
    <div className="mt-3 border-t border-border pt-3">
      <p className="mb-1 text-xs font-medium text-muted-foreground">
        {heading}
      </p>
      {children}
    </div>
  );
}

function ControlCard({
  control,
}: Readonly<{ control: ControlWorkspaceControl }>) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
        <div>
          <span className="font-mono text-sm font-semibold">{control.uid}</span>
          <span className="ml-2 text-sm text-muted-foreground">
            {control.title}
          </span>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {control.status}
          </span>
          <span className="rounded bg-muted px-1.5 py-0.5 text-xs">
            {control.controlFunction}
          </span>
        </div>
      </div>

      <div className="mb-3 flex flex-wrap gap-1.5">
        {control.queueReasons.map((reason) => (
          <QueueBadge key={reason} reason={reason} />
        ))}
      </div>

      {(control.descriptionPreview || control.objectivePreview) && (
        <div className="mb-3 space-y-1 text-sm text-muted-foreground">
          {control.descriptionPreview && <p>{control.descriptionPreview}</p>}
          {control.objectivePreview && <p>{control.objectivePreview}</p>}
        </div>
      )}

      <dl className="grid gap-2 md:grid-cols-4">
        <MetadataRow label="Owner" value={control.owner ?? "Unassigned"} />
        <MetadataRow label="Category" value={control.category} />
        <MetadataRow label="Source" value={control.source} />
        <MetadataRow
          label="Implementation scope"
          value={control.implementationScopePreview}
        />
      </dl>

      {control.scopedImplementations.length > 0 && (
        <DetailList heading="Scoped implementations">
          <ul className="space-y-1 text-xs">
            {control.scopedImplementations.map((scoped) => (
              <li key={scoped.id}>
                <span className="font-mono">{scoped.uid}</span>
                {" - "}
                <span>{scoped.name}</span>
                {scoped.operationalAssetUid && (
                  <span className="text-muted-foreground">
                    {" "}
                    ({scoped.operationalAssetUid}
                    {scoped.operationalAssetName
                      ? `, ${scoped.operationalAssetName}`
                      : ""}
                    )
                  </span>
                )}
                {scoped.implementationScope && (
                  <p className="text-muted-foreground">
                    {scoped.implementationScope}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </DetailList>
      )}

      {control.tests.length > 0 && (
        <DetailList heading="Control tests">
          <ul className="space-y-1 text-xs">
            {control.tests.map((test) => (
              <li key={test.id}>
                <span className="font-mono">{test.uid}</span>
                {" - "}
                <span>{test.methodology}</span>
                {" / "}
                <span>{test.conclusion}</span>
                <span className="text-muted-foreground">
                  {" "}
                  ({test.testDate}, {test.testerIdentity})
                </span>
                {test.notesPreview && (
                  <p className="text-muted-foreground">{test.notesPreview}</p>
                )}
              </li>
            ))}
          </ul>
        </DetailList>
      )}

      {control.assessments.length > 0 && (
        <DetailList heading="Effectiveness assessments">
          <ul className="space-y-1 text-xs">
            {control.assessments.map((assessment) => (
              <li key={assessment.id}>
                <span className="font-mono">{assessment.uid}</span>
                {" - design "}
                <span>{assessment.designEffectiveness}</span>
                {", operating "}
                <span>{assessment.operatingEffectiveness}</span>
                <span className="text-muted-foreground">
                  {" "}
                  ({assessment.assessedAt}, {assessment.assessor})
                </span>
              </li>
            ))}
          </ul>
        </DetailList>
      )}

      {control.evidence.length > 0 && (
        <DetailList heading="Evidence">
          <ul className="space-y-1 text-xs">
            {control.evidence.map((evidence) => (
              <li key={evidence.id}>
                <span className="font-mono">{evidence.uid}</span>
                {" - "}
                <span>{evidence.title}</span>
                <span className="text-muted-foreground">
                  {" "}
                  ({evidence.evidenceType}, {evidence.derivedAt})
                </span>
                <p className="text-muted-foreground">
                  {evidence.summaryPreview}
                </p>
              </li>
            ))}
          </ul>
        </DetailList>
      )}

      {control.findings.length > 0 && (
        <DetailList heading="Findings and exceptions">
          <ul className="space-y-1 text-xs">
            {control.findings.map((finding) => (
              <li key={finding.id}>
                <span className="font-mono">{finding.uid}</span>
                {" - "}
                <span>{finding.title}</span>
                <span className="text-muted-foreground">
                  {" "}
                  ({finding.severity}, {finding.status}
                  {finding.dueDate ? `, due ${finding.dueDate}` : ""})
                </span>
              </li>
            ))}
          </ul>
        </DetailList>
      )}

      {control.riskMappings.length > 0 && (
        <DetailList heading="Risk mappings">
          <ul className="space-y-1 text-xs">
            {control.riskMappings.map((mapping) => (
              <li key={mapping.id}>
                <span>{mapping.controlRole}</span>
                {" - "}
                <span>
                  {mapping.targetIdentifier ?? "Unidentified target"}
                  {mapping.targetTitle ? `, ${mapping.targetTitle}` : ""}
                </span>
                {mapping.mappingObjective && (
                  <p className="text-muted-foreground">
                    {mapping.mappingObjective}
                  </p>
                )}
                {mapping.evidenceRefs.length > 0 && (
                  <ul className="mt-1 space-y-0.5 text-muted-foreground">
                    {mapping.evidenceRefs.map((ref) => (
                      <li key={`${mapping.id}-${ref.evidenceRef}`}>
                        <span className="font-mono">{ref.evidenceRef}</span>
                        {ref.evidenceNotePreview
                          ? `, ${ref.evidenceNotePreview}`
                          : ""}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        </DetailList>
      )}
    </div>
  );
}

export function ControlAssuranceWorkspace() {
  const [filters, setFilters] = useState<ControlAssuranceWorkspaceFilters>({});
  const { data, isLoading, isError, error } =
    useControlAssuranceWorkspace(filters);

  const currentCount =
    data?.controls.filter((control) => control.queueReasons.includes("CURRENT"))
      .length ?? 0;
  const openExceptionCount =
    data?.controls.filter((control) =>
      control.queueReasons.includes("OPEN_EXCEPTION"),
    ).length ?? 0;
  const assessmentGapCount =
    data?.controls.filter((control) =>
      control.queueReasons.includes("ASSESSMENT_MISSING"),
    ).length ?? 0;

  return (
    <WorkspaceShell
      title="Control and Assurance Workspace"
      description="Project-scoped control catalog entries, implementations, tests, evidence, assessments, exceptions, and owner queues."
      controls={<ScopeControls filters={filters} onChange={setFilters} />}
      isLoading={isLoading}
      isError={isError}
      error={error}
      hasData={!!data}
    >
      {data && (
        <>
          <div className="grid gap-3 md:grid-cols-4">
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Controls</p>
              <p className="text-xl font-semibold">{data.controlCount}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Current</p>
              <p className="text-xl font-semibold">{currentCount}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Open exceptions</p>
              <p className="text-xl font-semibold">{openExceptionCount}</p>
            </div>
            <div className="rounded-lg border border-border bg-card p-3">
              <p className="text-xs text-muted-foreground">Assessment gaps</p>
              <p className="text-xl font-semibold">{assessmentGapCount}</p>
            </div>
          </div>

          <section aria-labelledby="controls-heading">
            <h2 id="controls-heading" className="mb-2 text-lg font-medium">
              Controls
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                ({data.controlCount})
              </span>
            </h2>
            {data.controls.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No controls match the current filters.
              </p>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {data.controls.map((control) => (
                  <ControlCard key={control.id} control={control} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </WorkspaceShell>
  );
}
