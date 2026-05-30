# Ground Control REST API

REST API for direct HTTP usage. Pre-alpha. The `dev` profile disables
authentication for local work; **production deployments require it**
(see [ADR-026](../architecture/adrs/026-rest-api-access-control.md) and
[`docs/deployment/DEPLOYMENT.md`](deployment/DEPLOYMENT.md)).

## Authentication

When `groundcontrol.security.enabled=true`:

- Send `Authorization: Bearer <token>` on every `/api/v1/**` request.
- `/api/v1/admin/**`, `/api/v1/embeddings/**`, `/api/v1/analysis/sweep/**`,
  and `/api/v1/pack-registry/**` require a token whose configured `role`
  is `ADMIN`. Other `/api/v1/**` paths accept any authenticated token.
- `/actuator/health` and `/actuator/info` are anonymous; the OpenAPI
  schema is gated by `groundcontrol.security.openapi-public`.
- An optional CIDR allowlist (`groundcontrol.security.ip-allowlist`)
  rejects out-of-range source addresses with 403 `access_denied` before
  the token check runs.

Errors use the standard envelope:

```
401 → {"error": {"code": "authentication_required", "message": "..."}}
403 → {"error": {"code": "access_denied", "message": "..."}}
```

## Base URL

```
http://localhost:8000/api/v1/
```

## Endpoints

### Requirements

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/requirements` | RequirementRequest | 201 | Create requirement |
| GET | `/requirements` |—| 200 | List requirements (paginated, filterable) |
| GET | `/requirements/{id}` |—| 200 | Get requirement by UUID |
| GET | `/requirements/uid/{uid}` |—| 200 | Get requirement by UID |
| PUT | `/requirements/{id}` | UpdateRequirementRequest | 200 | Update requirement (partial) |
| POST | `/requirements/{id}/transition` | `{ "status": "ACTIVE" }` | 200 | Transition status |
| POST | `/requirements/bulk/transition` | BulkStatusTransitionRequest | 200 | Bulk transition status |
| POST | `/requirements/{id}/clone` | CloneRequirementRequest | 201 | Clone requirement |
| POST | `/requirements/{id}/archive` |—| 200 | Archive requirement |

### Relations

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/requirements/{id}/relations` | RelationRequest | 201 | Create relation |
| GET | `/requirements/{id}/relations` |—| 200 | List relations |
| DELETE | `/requirements/{id}/relations/{relationId}` |—| 204 | Delete relation |

### Traceability

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/requirements/{id}/traceability` | TraceabilityLinkRequest | 201 | Create traceability link |
| GET | `/requirements/{id}/traceability` |—| 200 | List traceability links |
| GET | `/requirements/traceability/by-artifact` |—| 200 | Reverse lookup: find links by artifact |
| GET | `/requirements/traceability/matrix` |—| 200 | Read-only traceability matrix: requirement rows with links projected as cells per link type, per-link-type coverage columns, and ACTIVE-requirement gap counts. Filters: `wave`, `status`, `linkType` (GC-Q003) |
| DELETE | `/requirements/{id}/traceability/{linkId}` |—| 204 / 404 | Delete traceability link. Returns 404 if `linkId` does not belong to `id`. |

`GET /requirements/traceability/by-artifact` accepts query parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `artifactType` | enum | GITHUB_ISSUE, PULL_REQUEST, CODE_FILE, ADR, CONFIG, POLICY, TEST, SPEC, PROOF, DOCUMENTATION, RISK_SCENARIO, CONTROL |
| `artifactIdentifier` | string | Artifact identifier (for example, repo-relative path, issue number, ADR UID) |

### Audit History

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/requirements/{id}/history` |—| 200 | Requirement revision history |
| GET | `/requirements/{id}/relations/{relationId}/history` |—| 200 / 404 | Relation revision history. Returns 404 if `relationId` does not belong to `id` (the requirement is neither the source nor the target of the relation). |
| GET | `/requirements/{id}/traceability/{linkId}/history` |—| 200 / 404 | Traceability link revision history. Returns 404 if `linkId` does not belong to `id`. |
| GET | `/requirements/{id}/timeline` |—| 200 | Unified audit timeline |

`GET /requirements/{id}/timeline` accepts query parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `changeCategory` | enum | REQUIREMENT, RELATION, TRACEABILITY_LINK |
| `from` | ISO-8601 instant | Start of date range |
| `to` | ISO-8601 instant | End of date range |
| `limit` | integer | Max entries to return (default 100) |
| `offset` | integer | Number of entries to skip (default 0) |

**TimelineEntryResponse:**

```json
{
  "revisionNumber": 3,
  "revisionType": "MOD",
  "timestamp": "2026-03-21T04:00:00Z",
  "actor": "user@example.com",
  "changeCategory": "REQUIREMENT",
  "entityId": "uuid",
  "snapshot": { "title": "New Title", "status": "ACTIVE", "..." : "..." },
  "changes": { "title": { "oldValue": "Old Title", "newValue": "New Title" } }
}
```

### Analysis

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/analysis/cycles` |—| 200 | Detect dependency cycles |
| GET | `/analysis/orphans` |—| 200 | Find orphan requirements |
| GET | `/analysis/coverage-gaps?linkType=X` |—| 200 | Find coverage gaps by link type |
| GET | `/analysis/impact/{id}` |—| 200 | Transitive impact analysis |
| GET | `/analysis/cross-wave` |—| 200 | Cross-wave dependency violations |
| GET | `/analysis/consistency-violations` |—| 200 | Detect consistency violations |
| GET | `/analysis/completeness` |—| 200 | Analyze completeness |
| GET | `/analysis/work-order` |—| 200 | Topological work order |
| GET | `/analysis/dashboard-stats` |—| 200 | Aggregate project health stats |
| GET | `/analysis/semantic-similarity` |—| 200 | Find semantically similar requirement pairs |
| GET | `/analysis/status-drift` |—| 200 | Flag DRAFT requirements that have implementation evidence |
| POST | `/analysis/sweep` |—| 200 | Run analysis sweep on one project |
| POST | `/analysis/sweep/all` |—| 200 | Run analysis sweep on all projects |

**CycleResponse** (`GET /analysis/cycles`):

```json
[
  {
    "members": ["REQ-A", "REQ-B", "REQ-C", "REQ-A"],
    "edges": [
      { "sourceUid": "REQ-A", "targetUid": "REQ-B", "relationType": "DEPENDS_ON" },
      { "sourceUid": "REQ-B", "targetUid": "REQ-C", "relationType": "DEPENDS_ON" },
      { "sourceUid": "REQ-C", "targetUid": "REQ-A", "relationType": "PARENT" }
    ]
  }
]
```

Each cycle lists the member UIDs (closing back to the start) and the edges that
form it, including the relation type between each consecutive pair.

`GET /analysis/semantic-similarity` accepts query parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `threshold` | double | 0.85 | Minimum similarity score (0–1) |

`GET /analysis/status-drift` flags `DRAFT` requirements that carry independent
evidence of implementation or design completion (read-only—it never transitions
requirements or creates links). Query parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `minimumConfidence` | enum (`HIGH` \| `MEDIUM` \| `LOW`) | `MEDIUM` | Lowest confidence band to report (default reports `HIGH` and `MEDIUM`; `LOW` is opt-in) |

**StatusDriftResponse** (`GET /analysis/status-drift`):

```json
{
  "draftRequirementsScanned": 14,
  "minimumConfidence": "MEDIUM",
  "findings": [
    {
      "uid": "GC-T010",
      "title": "Risk Assessment Result Entity",
      "confidence": "HIGH",
      "strongestSignal": "IMPLEMENTS_LINK_ON_DRAFT",
      "evidence": [
        {
          "signal": "IMPLEMENTS_LINK_ON_DRAFT",
          "confidence": "HIGH",
          "artifactType": "GITHUB_ISSUE",
          "artifactIdentifier": "826",
          "artifactTitle": "GC-T010: Risk Assessment Result Entity",
          "artifactUrl": "https://github.com/KeplerOps/Ground-Control/issues/826",
          "detail": "IMPLEMENTS link on a DRAFT requirement"
        }
      ]
    }
  ]
}
```

Evidence signals, strongest first: `IMPLEMENTS_LINK_ON_DRAFT` (`HIGH`);
`ACCEPTED_ADR_DOCUMENTS_LINK`, `LINKED_GITHUB_ISSUE`, `LINKED_PULL_REQUEST`
(`MEDIUM`); `LINKED_CODE_ARTIFACT`, `LINKED_DOC_ARTIFACT` (`LOW`). All signals are
derived from the requirement's own project (its canonical traceability links and
accepted ADR records), so the endpoint never reads the project-unscoped GitHub
issue/PR sync tables or the filesystem. A finding's `confidence` is the strongest
band across its `evidence`. Status drift is also surfaced inside
`POST /analysis/sweep` as a new problem class (`statusDrift` array, counted in
`totalProblems`).

**SimilarityResultResponse:**

```json
{
  "totalRequirements": 50,
  "embeddedCount": 48,
  "pairsAnalyzed": 1128,
  "threshold": 0.85,
  "pairs": [
    {
      "uid1": "REQ-012",
      "title1": "User authentication via SSO",
      "uid2": "REQ-037",
      "title2": "Single sign-on login support",
      "score": 0.93
    }
  ]
}
```

### GRC Analysis (GC-L007)

GRC-specific analyses live under `/analysis/grc/*` and ride on existing
substrates: `EvidenceArtifact` (derivedAt / supersededByArtifactId / sources),
`Observation` (observedAt / expiresAt), `ControlTest` (testDate), and
`OperationalAsset` (filtered by `AssetType.THIRD_PARTY` for vendor analyses).
Every response is methodology-attributed and structured for agent
consumption: `analysisKind`, `project`, `asOf`, `derivationMethod`,
`inputs`/`outputs`/`limitations` sections, per
`architecture/notes/mcp-grc-analysis-tools-preflight.md`. No generic
`risk_score`; no executions of FAIR / FAIR-CAM methodology engines (those
are tracked in GC-T011 / GC-I017 and ship their own analysis endpoints when
the engine lands). NIST SP 800-30 Rev. 1 ships under GC-T014 / #721 as the
`nist-sp-800-30` endpoint below.

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/analysis/grc/evidence-freshness` |—| 200 | Per-evidence / per-observation / per-control-test freshness state given an `asOf` and `freshnessWindowDays`. |
| GET | `/analysis/grc/observation-projection?mode=ASSET_EXPOSURE\|CONTROL_STATE` |—| 200 | Current-state projection from observations; ASSET_EXPOSURE flags assets with active observations; CONTROL_STATE joins through `ControlEffectivenessAssessment`. |
| GET | `/analysis/grc/vendor-risk` |—| 200 | Aggregation over `OperationalAsset` of `AssetType.THIRD_PARTY` (findings, observations, evidence freshness, mapped controls). |
| GET | `/analysis/grc/nist-sp-800-30` |—| 200 | NIST SP 800-30 Rev. 1 methodology-attributed view over `RiskAssessmentResult` rows bound to a `MethodologyProfile` whose family is `NIST_SP800_30_R1`. Decodes inputs into threat source, threat event (`ADVERSARIAL` / `NON_ADVERSARIAL`), vulnerabilities, predisposing conditions, threat-source relevance, multi-dimensional likelihood, impact level, and assessment timeframe; computes overall likelihood (analyst-supplied or derived per Table G-5) and risk level (per Table I-2) as ordinal bands with explicit `scale`/`units` and a matrix cell label. |
| GET | `/analysis/grc/portfolio` |—| 200 | GRC Portfolio Reporting roll-up (GC-Q013): risk posture (scenario/assessment/treatment/register distributions, reassessment + overdue-review signals), control health (status + design/operating effectiveness distributions, unassessed + unmapped counts), evidence freshness counts, finding trends (severity/status/type, open + overdue), asset criticality concentration (criticality/environment/scope), and methodology-family (FAIR/NIST/ISO) summaries. Each dimension carries actionable drill-down id lists behind its key signal counts (critical assets, unmapped + unassessed controls, overdue register reviews, open + overdue findings). Read-only projection; no new materialized state. Accepts `asOf`, `freshnessWindowDays`. |
| GET | `/analysis/grc/compliance-posture` |—| 200 | Per-framework, per-element compliance posture (GC-I002 / GC-L007 carve-out) over the `ComplianceFrameworkMapping` aggregate. Returns the methodology-attributed envelope (`analysisKind: "compliance_posture"`, `inputs`, `frameworks[].elements[].mappings`, `counts.coverageLevelCounts`, `limitations`). Accepts optional `framework` (one of `SOC2`, `SOX`, `ISO_27001`, `NIST_CSF`, `PCI_DSS`). |
| GET | `/analysis/grc/framework-gap` |—| 200 | Cross-framework gap analysis (GC-I007 / GC-L007 carve-out). Categorizes each element by `GapSeverity` (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `NONE`) derived from mapping coverage shape (`FULL` / `PARTIAL` / `COMPENSATING`). Accepts optional `framework` and `minSeverity`. |

`GET /analysis/grc/evidence-freshness` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp; freshness is computed against this |
| `freshnessWindowDays` | int (positive) | 90 | Items older than this are flagged `STALE`. Non-positive values return `400`. |
| `includeSuperseded` | boolean | false | If true, `SUPERSEDED` artifacts are still surfaced (state-labeled) |
| `assetId` | UUID |—| Narrow to evidence/observations attached to this asset. Must belong to the resolved project or `404` is returned. |
| `controlId` | UUID |—| Narrow to evidence/observations/tests for this control. When supplied without `assetId`, observations are not joinable from controls today; the response surfaces an empty `observations` list and a `limitations` entry explaining the carve-out. When supplied with `assetId`, sections are intersected. |

`GET /analysis/grc/observation-projection` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp; expired observations are flagged `EXPIRED` |
| `mode` | enum (`ASSET_EXPOSURE` \| `CONTROL_STATE`) | required | Which projection to run |
| `assetId` | UUID |—| Narrow to observations on this asset |
| `controlId` | UUID |—| Narrow `CONTROL_STATE` to this control (ignored for `ASSET_EXPOSURE`) |

`GET /analysis/grc/vendor-risk` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp; freshness is computed against this |
| `freshnessWindowDays` | int (positive) | 90 | Window used to label vendor-attached evidence as `STALE`/`FRESH`. Non-positive values return `400`. |
| `vendorAssetId` | UUID |—| Narrow to a single third-party asset (otherwise rolls up every `AssetType.THIRD_PARTY` row). Must belong to the resolved project or `404`. |

`GET /analysis/grc/nist-sp-800-30` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the response envelope |
| `riskAssessmentResultId` | UUID |—| Filter to a single `RiskAssessmentResult`; returns `404` if missing, `422` if the row is not bound to a `NIST_SP800_30_R1` `MethodologyProfile` |
| `riskScenarioId` | UUID |—| Narrow to assessments under one `RiskScenario` |

Response shape: top-level `analysisKind: "nist_assessment"`, `project`,
`asOf`, `derivationMethod` (`"nist-sp800-30-rev1-5x5-matrix-v1"`), `scale`
(`"ordinal"`), `units` (`"qualitative ordinal levels"`),
`matrixConversionRule` (Table I-2 attribution), an `assessments` array, a
`counts` summary (`total`, `byRiskLevel`, `withLimitations`), and a
top-level `limitations` array. Each assessment item carries
`assessmentId` / `riskScenarioId` / `methodologyProfileId` / `profileKey`
(`NIST_SP800_30_R1`) / `family` / `version` / `assessmentAt` /
`timeHorizon` / `analystIdentity` / `approvalState`, structured `inputs`
(`threatSource`, `threatEvent`, `threatEventKind`, `vulnerabilities`,
`predisposingConditions`, `threatSourceRelevance`, `likelihoodInitiation`,
`likelihoodAdverseImpact`, `likelihoodOverall`, `impactLevel`,
`assessmentTimeframe`), structured `outputs` (`overallLikelihood`,
`impactLevel`, `riskLevel`, `matrixCell` (for example `L3-I4`), `derivation`),
`evidenceRefs`, and per-row `limitations`. Adversarial-only fields
(`threat_source_characteristics.capability` / `intent` / `targeting`) are
preserved verbatim from inputs but a `limitations` entry is emitted when
they appear on a non-adversarial event. Ordinal bands MUST NOT be
normalized into a cross-methodology numeric score without an explicit
method label and conversion rule.

Every response carries a `limitations` array. For the vendor-risk endpoint
that array always includes a note that vendors are modeled as
`OperationalAsset` rows of `AssetType.THIRD_PARTY` rather than a first-class
vendor aggregate (per the GC-L009 carve-out from GC-L006). When external
framework identifiers, missing evidence, or unvalidated methodology schemas
are involved, additional `limitations` entries are emitted.

#### Methodology-aware Aggregate Risk Reporting (GC-T008)

The five GC-T008 projections are read-only views over the existing
`RiskAssessmentResult`, `RiskRegisterRecord`, and Envers audit substrates.
Each response carries the ADR-035 methodology-attributed envelope
(`analysisKind`, `project`, `asOf`, `derivationMethod`, `scale`, `units`,
structured `inputs`, `limitations`) so consumers never confuse a qualitative
ordinal band with a quantitative loss figure. The heat-map and top-N
endpoints additionally surface `methodologyProfileId` / `methodologyFamily`
when the projection is restricted to a single profile; mixing methodology
families in a single result emits an explicit limitation.

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/analysis/grc/risk-heatmap` |—| 200 | Qualitative likelihood × impact heat map over the latest-per-scenario `RiskAssessmentResult`. FAIR rows are excluded (quantitative, not band-plottable) with a `limitations` entry; NIST and ISO_27005 / CUSTOM ordinal profiles plot using the NIST band vocabulary. |
| GET | `/analysis/grc/risk-distribution` |—| 200 | Count of `RiskRegisterRecord` rows bucketed by `groupBy` axis (`CATEGORY` / `STATUS` / `OWNER` / `ASSET_CRITICALITY`). Records with no resolvable key fall into an `UNCLASSIFIED` bucket; `ASSET_CRITICALITY` always carries a carve-out limitation because per-record asset attribution is not maintained directly on `RiskRegisterRecord`. |
| GET | `/analysis/grc/risk-top-n` |—| 200 | Top-N risk scenarios ranked by `orderBy` over the latest-per-scenario `RiskAssessmentResult`. `CURRENT_ASSESSMENT_OUTPUT` ranks by the qualitative `risk_level` carried in `computedOutputs`; `ASSESSMENT_AT_DESC` ranks by recency. FAIR rows are flagged on the per-entry `limitations`; mixing methodology families in the same N adds a project-level limitation. |
| GET | `/analysis/grc/risk-trends` |—| 200 | Risk trend points from the Envers audit history of `RiskRegisterRecord` bucketed into `WEEK` / `MONTH` / `QUARTER` intervals. Counts revisions per status / per `RevisionType` per bucket; actor identity is not surfaced (ADR-033). |
| GET | `/analysis/grc/risk-posture` |—| 200 | Executive risk posture: `RiskRegisterRecord` open/accepted/closed status distribution, latest-per-scenario `RiskAssessmentResult` approval-state distribution, and the count of assessments flagged for reassessment (`reassessmentRequiredAt` non-null). Always carries a `limitations` entry recording that detailed appetite/tolerance evaluation is deferred to the cluster-1 `RiskAppetiteEvaluator` kernel. |

`GET /analysis/grc/risk-heatmap` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the envelope |
| `methodologyProfileId` | UUID |—| Restrict plot to rows under one methodology profile; otherwise every project assessment row is considered. |

`GET /analysis/grc/risk-distribution` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the envelope |
| `groupBy` | enum (`CATEGORY` \| `STATUS` \| `OWNER` \| `ASSET_CRITICALITY`) | required | Bucket axis |

`GET /analysis/grc/risk-top-n` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the envelope |
| `limit` | int (positive, ≤200) | 10 | Maximum entries to return |
| `orderBy` | enum (`CURRENT_ASSESSMENT_OUTPUT` \| `ASSESSMENT_AT_DESC`) | `CURRENT_ASSESSMENT_OUTPUT` | Ranking mode |

`GET /analysis/grc/risk-trends` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the envelope |
| `from` | ISO-8601 instant | `to − 365d` | Window start (default 12 months before `to`) |
| `to` | ISO-8601 instant | `asOf` | Window end |
| `bucket` | enum (`WEEK` \| `MONTH` \| `QUARTER`) | `MONTH` | Bucket size |

`GET /analysis/grc/risk-posture` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the envelope |

#### Compliance Framework Analysis (GC-I002 / GC-I007 / GC-L011)

`GET /analysis/grc/compliance-posture` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the response envelope |
| `framework` | `ComplianceFrameworkIdentifier` |—| Narrow to a single framework. When omitted, every framework with at least one mapping is rolled up. |

Response shape: top-level `analysisKind: "compliance_posture"`, `project`,
`asOf`, `derivationMethod` (`"compliance-framework-mapping-projection-v1"`),
`inputs`, a `frameworks` array (each entry with `framework`,
`frameworkIdentifier` (when external), `frameworkVersion`, `elements`, and
per-coverage-level counts), a `counts` summary
(`totalFrameworks`, `totalElements`, `totalMappings`, `coverageLevelCounts`),
and a top-level `limitations` array. Each element rollup lists the
`mappings` that resolve it with endpoint UUIDs.

`GET /analysis/grc/framework-gap` accepts:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `project` | string | auto-resolved | Project identifier |
| `asOf` | ISO-8601 instant | `now()` | Evaluation timestamp echoed in the response envelope |
| `framework` | `ComplianceFrameworkIdentifier` |—| Narrow to a single framework |
| `minSeverity` | `GapSeverity` |—| Drop elements less severe than this (severity order: `CRITICAL` > `HIGH` > `MEDIUM` > `LOW` > `NONE`) |

Response shape: top-level `analysisKind: "cross_framework_gap"`, `project`,
`asOf`, `derivationMethod`
(`"compliance-framework-mapping-gap-projection-v1"`), `inputs`,
a `frameworks` array (each entry with `framework`, `frameworkIdentifier`,
`frameworkVersion`, `elementGaps`, and `bySeverity` counts), and a
`counts` summary (`totalElements`, `bySeverity`). Each element gap entry
carries `frameworkElement`, `severity`, `coverageStatus`
(`FULL` / `PARTIAL` / `COMPENSATING_ONLY` / `UNMAPPED`), and the
`requirementIds` / `controlIds` of the mappings that resolve the element.

### Embeddings

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/embeddings/{requirementId}` |—| 200 | Embed a single requirement |
| GET | `/embeddings/{requirementId}/status` |—| 200 | Get embedding status |
| POST | `/embeddings/batch?project=&force=false` |—| 200 | Batch embed all requirements in a project |
| DELETE | `/embeddings/{requirementId}` |—| 204 | Delete embedding |

Requires `GC_EMBEDDING_PROVIDER=openai` and `GC_EMBEDDING_API_KEY` to be set.
When no provider is configured, endpoints return `provider_unavailable` status
(graceful degradation).

**EmbeddingStatusResponse** (`GET /embeddings/{id}/status`):

```json
{
  "requirementId": "uuid",
  "hasEmbedding": true,
  "isStale": false,
  "modelMismatch": false,
  "currentModelId": "text-embedding-3-small",
  "embeddingModelId": "text-embedding-3-small",
  "embeddedAt": "2026-03-22T03:00:00Z"
}
```

### Baselines

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/baselines?project=` | BaselineRequest | 201 | Create baseline |
| GET | `/baselines?project=` |—| 200 | List baselines |
| GET | `/baselines/{id}` |—| 200 | Get baseline |
| GET | `/baselines/{id}/snapshot` |—| 200 | Requirement snapshot at baseline |
| GET | `/baselines/{id}/compare/{otherId}` |—| 200 | Compare two baselines |
| DELETE | `/baselines/{id}` |—| 204 | Delete baseline |

**BaselineRequest:**

```json
{
  "name": "v1.0",
  "description": "First release baseline"
}
```

**BaselineComparisonResponse** (`GET /baselines/{id}/compare/{otherId}`):

```json
{
  "baselineId": "uuid",
  "baselineName": "v1.0",
  "otherBaselineId": "uuid",
  "otherBaselineName": "v2.0",
  "addedCount": 2,
  "removedCount": 0,
  "modifiedCount": 1,
  "added": [...],
  "removed": [...],
  "modified": [{ "requirementId": "uuid", "uid": "REQ-001", "before": {...}, "after": {...} }]
}
```

### Document Grammar

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| PUT | `/documents/{id}/grammar` | Grammar JSON | 200 | Set/replace grammar |
| GET | `/documents/{id}/grammar` |—| 200 | Get grammar |
| DELETE | `/documents/{id}/grammar` |—| 204 | Remove grammar |

**Grammar JSON:**

```json
{
  "fields": [
    {"name": "acceptance_criteria", "type": "STRING", "required": false},
    {"name": "risk_level", "type": "ENUM", "required": true, "enumValues": ["LOW", "MEDIUM", "HIGH"]}
  ],
  "allowedRequirementTypes": ["FUNCTIONAL", "NON_FUNCTIONAL"],
  "allowedRelationTypes": ["PARENT", "DEPENDS_ON", "REFINES"]
}
```

Field types: `STRING`, `INTEGER`, `BOOLEAN`, `ENUM`. Declarative metadata—no runtime enforcement.

### Document Reading Order

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/documents/{id}/reading-order` |—| 200 | Full document in reading order |

Returns the document with all sections nested, each containing its content items
(requirement references and text blocks) in authored sequence.

### Section Content

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/sections/{sectionId}/content` | SectionContentRequest | 201 | Add content item |
| GET | `/sections/{sectionId}/content` |—| 200 | List content in order |
| PUT | `/sections/content/{id}` | UpdateSectionContentRequest | 200 | Update content item |
| DELETE | `/sections/content/{id}` |—| 204 | Delete content item |

**SectionContentRequest:**

```json
{
  "contentType": "REQUIREMENT",
  "requirementId": "uuid",
  "sortOrder": 0
}
```

or for text blocks:

```json
{
  "contentType": "TEXT_BLOCK",
  "textContent": "This section describes...",
  "sortOrder": 1
}
```

### Sections

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/documents/{documentId}/sections` | SectionRequest | 201 | Create section |
| GET | `/documents/{documentId}/sections` |—| 200 | List sections (flat) |
| GET | `/documents/{documentId}/sections/tree` |—| 200 | Get section tree (nested) |
| GET | `/sections/{id}` |—| 200 | Get section |
| PUT | `/sections/{id}` | UpdateSectionRequest | 200 | Update section |
| DELETE | `/sections/{id}` |—| 204 | Delete section (cascades children) |

**SectionRequest:**

```json
{
  "parentId": null,
  "title": "Chapter 1: Introduction",
  "description": "Overview section",
  "sortOrder": 0
}
```

Sections support arbitrary nesting—set `parentId` to a section UUID to create a child.
The tree endpoint returns a nested JSON structure with `children` arrays.

### Documents

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/documents?project=` | DocumentRequest | 201 | Create document |
| GET | `/documents?project=` |—| 200 | List documents |
| GET | `/documents/{id}` |—| 200 | Get document |
| PUT | `/documents/{id}` | UpdateDocumentRequest | 200 | Update document |
| DELETE | `/documents/{id}` |—| 204 | Delete document |

**DocumentRequest:**

```json
{
  "title": "System Requirements Specification",
  "version": "1.0.0",
  "description": "Top-level SRS document"
}
```

### Architecture Decision Records

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/adrs?project=` | AdrRequest | 201 | Create ADR |
| GET | `/adrs?project=` |—| 200 | List ADRs |
| GET | `/adrs/{id}` |—| 200 | Get ADR by UUID |
| GET | `/adrs/uid/{uid}?project=` |—| 200 | Get ADR by UID |
| PUT | `/adrs/{id}` | UpdateAdrRequest | 200 | Update ADR (partial) |
| DELETE | `/adrs/{id}` |—| 204 | Delete ADR |
| PUT | `/adrs/{id}/status` | `{ "status": "ACCEPTED" }` | 200 | Transition status |
| GET | `/adrs/{id}/requirements` |—| 200 | Get linked requirements (reverse traceability) |

**AdrRequest:**

```json
{
  "uid": "ADR-030",
  "title": "On-prem Hetzner Deployment",
  "decisionDate": "2026-05-03",
  "context": "Need a deployment target that lifts the JVM memory ceiling and removes the AWS account dependency",
  "decision": "Run the docker-compose stack on red-dragon, tailnet-only, image pulled from GHCR",
  "consequences": "Eliminates EC2/EBS/S3/DLM/IAM surface; capacity headroom for AGE and the embedding pipeline; no marginal cost"
}
```

**Status transitions:** PROPOSED → ACCEPTED → DEPRECATED | SUPERSEDED

### Operational Assets

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/assets?project=` | AssetRequest | 201 | Create asset |
| GET | `/assets?project=&type=&owner=&steward=&environment=&criticality=&scope=&subtype=` |—| 200 | List assets (any combination of filters is optional; `subtype` is exact-match per the GC-M011 subtype catalog) |
| GET | `/assets/{id}` |—| 200 | Get asset by UUID |
| GET | `/assets/uid/{uid}?project=` |—| 200 | Get asset by UID |
| PUT | `/assets/{id}` | UpdateAssetRequest | 200 | Update asset (partial) |
| DELETE | `/assets/{id}` |—| 204 | Delete asset (cascade deletes relations) |
| POST | `/assets/{id}/archive` |—| 200 | Archive (soft-delete) asset |

**AssetRequest:**

```json
{
  "uid": "ASSET-001",
  "name": "Production Database",
  "description": "Primary PostgreSQL instance",
  "assetType": "DATABASE",
  "owner": "alice@example.com",
  "steward": "platform-sre",
  "environment": "PRODUCTION",
  "criticality": "CRITICAL",
  "businessContext": "Primary system of record for billing; PCI-DSS in scope.",
  "scopeDesignation": "IN_SCOPE",
  "subtype": "rds_postgres",
  "metadata": {
    "cloud_account_id": "1234567890",
    "region": "us-west-2"
  }
}
```

`owner`, `steward`, and `businessContext` are free-text labels (≤ 200 chars on `owner`/`steward`; `businessContext` is `TEXT`). All six GC-M012 metadata fields are optional on `AssetRequest` and on `UpdateAssetRequest`. On the update path, `null` / absent means "leave field unchanged" (mirrors the existing `name`/`description`/`assetType` null-means-unchanged semantics). To reset a previously designated metadata field back to NULL ("not designated"), send the paired clear flag (`clearOwner`, `clearSteward`, `clearEnvironment`, `clearCriticality`, `clearBusinessContext`, or `clearScopeDesignation`) as `true`. The clear flag wins over a same-payload assignment so the wire semantics stay unambiguous (the assign loses). This mirrors the `clearRootCauseAnalysis` / `clearOwner` / `clearDueDate` pattern on `UpdateFindingRequest`.

GC-M011 fields (`subtype`, `metadata`) follow the same null-means-unchanged / `clearSubtype` / `clearMetadata` convention on the update path. `subtype` is a narrower, project-defined classification under `assetType` (≤ 100 chars). `metadata` is a bounded key→scalar map (≤ 50 keys, key ≤ 100 chars, string value ≤ 4096 chars, scalar values only: strings / numbers / booleans / null). When a matching ACTIVE `AssetSubtypeSchema` (project + assetType + subtype) is registered, the validator additionally enforces the schema's field types, required fields, and bounds; otherwise only the universal bounds apply. `metadata` replacement is atomic—non-null `metadata` in `UpdateAssetRequest` replaces the entire map.

Asset types: `APPLICATION`, `SERVICE`, `SYSTEM`, `DATABASE`, `NETWORK`, `HOST`, `CONTAINER`, `IDENTITY`, `DATA_STORE`, `ENDPOINT`, `INTEGRATION`, `WORKLOAD`, `THIRD_PARTY`, `BOUNDARY`, `OTHER`

Asset criticality (GC-M012): `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`. Distinct from finding severity, risk level, control effectiveness, and assurance confidence per ADR-012 / `docs/CODING_STANDARDS.md`.

Asset environment (GC-M012): `PRODUCTION`, `STAGING`, `DEVELOPMENT`, `TEST`, `NON_PRODUCTION`, `OTHER`. `NON_PRODUCTION` is the umbrella value for assets that pre-date the more specific environment vocabulary.

Asset scope designation (GC-M012): `IN_SCOPE`, `OUT_OF_SCOPE`. Two-state explicit; the absence of either (NULL) means "not yet designated"—distinct from `archivedAt` (lifecycle), `quality_gate.scopeStatus`, control `implementationScope`, and risk `assetScopeSummary`.

List filters route through `OperationalAssetRepository.findByProjectIdAndArchivedAtIsNullAndFilters` so any combination of `type` / `owner` / `steward` / `environment` / `criticality` / `scope` query parameters is honored in a single JPQL pass; risk, control, audit, and reporting workflows consume this same surface rather than inventing per-workflow lookups.

### Asset Subtype Schemas (GC-M011 schema layering)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/assets/subtype-schemas?project=` | AssetSubtypeSchemaRequest | 201 | Register a new ACTIVE schema (auto-deprecates the prior ACTIVE entry for the same `(assetType, subtype)`) |
| GET | `/assets/subtype-schemas?project=&assetType=&subtype=` |—| 200 | List schemas; valid combinations are: neither (list all in project), `assetType` alone (list for that asset type), or both `assetType` + `subtype` (list for that exact pair). `subtype` alone without `assetType` is rejected with `asset_subtype_schema_filter_invalid` because the same subtype string may legitimately exist under different asset-type buckets |
| GET | `/assets/subtype-schemas/active?project=&assetType=&subtype=` |—| 200 | Get the single ACTIVE schema for an `(assetType, subtype)` |
| GET | `/assets/subtype-schemas/{id}` |—| 200 | Get schema by UUID |
| PUT | `/assets/subtype-schemas/{id}` | UpdateAssetSubtypeSchemaRequest | 200 | Replace description / schema body (atomic) |
| POST | `/assets/subtype-schemas/{id}/deprecate` |—| 200 | Mark schema DEPRECATED |

**AssetSubtypeSchemaRequest:**

```json
{
  "assetType": "WORKLOAD",
  "subtype": "linux_container_host",
  "schemaVersion": "v1",
  "description": "Linux host running a docker-compose stack on the operator tailnet",
  "schemaBody": {
    "fields": {
      "fqdn": {"type": "STRING", "required": true, "maxLength": 253},
      "tailnet_ip": {"type": "STRING", "required": false, "maxLength": 45},
      "kernel_version": {"type": "STRING", "maxLength": 64},
      "container_runtime": {"type": "ENUM", "required": true, "values": ["docker", "podman", "containerd"]}
    },
    "allowAdditional": false
  }
}
```

Schema field types: `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `ENUM`. `required` defaults to `false`. `maxLength` applies to `STRING` only; `minimum` / `maximum` apply to `INTEGER` / `NUMBER` only; `values` is the case-sensitive allowed list for `ENUM`. `allowAdditional: false` (default) rejects metadata keys not declared in `fields`.

Validation errors return HTTP `422` with the canonical `ErrorResponse` envelope and an `errorCode` of `asset_metadata_invalid`; the `detail` map identifies the offending field, the reason (`type_mismatch`, `required_field_missing`, `unknown_field`, `string_too_long`, `below_minimum`, `above_maximum`, `enum_value_not_allowed`, etc.), and the expected / actual values.

### Asset Relations

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/assets/{id}/relations` | AssetRelationRequest | 201 | Create typed relation |
| PUT | `/assets/{id}/relations/{relationId}` | UpdateAssetRelationRequest | 200 | Update relation metadata |
| GET | `/assets/{id}/relations` |—| 200 | List relations (incoming + outgoing) |
| DELETE | `/assets/{id}/relations/{relationId}` |—| 204 | Delete relation |

**AssetRelationRequest:**

```json
{
  "targetId": "uuid",
  "relationType": "DEPENDS_ON",
  "description": "Observed runtime dependency",
  "sourceSystem": "INVENTORY_SCAN",
  "externalSourceId": "inv-123",
  "collectedAt": "2026-04-01T12:00:00Z",
  "confidence": "0.80"
}
```

**UpdateAssetRelationRequest:**

```json
{
  "description": "Refined runtime dependency",
  "sourceSystem": "CMDB",
  "externalSourceId": "cmdb-789",
  "collectedAt": "2026-04-02T12:00:00Z",
  "confidence": "0.95"
}
```

**AssetRelationResponse fields:** `id`, `sourceId`, `sourceUid`, `targetId`, `targetUid`, `relationType`, `description`, `sourceSystem`, `externalSourceId`, `collectedAt`, `confidence`, `createdAt`, `updatedAt`

Relation types: `CONTAINS`, `DEPENDS_ON`, `COMMUNICATES_WITH`, `TRUST_BOUNDARY`, `SUPPORTS`, `ACCESSES`, `DATA_FLOW`

MCP surface: `gc_asset` with actions `relation_create`, `relation_update`, `relation_delete`. `relation_create` requires `source_id`, `target_id`, `relation_type`; optional body fields are `description`, `source_system`, `external_source_id`, `collected_at`, `confidence`, `knowledge_state`. `relation_update` requires `asset_id`, `relation_id`; accepts the same optional fields (excludes `target_id` and `relation_type` which are immutable after creation). `source_id` is a path parameter, not a body field.

### Asset Links (Cross-Entity Linking)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/assets/{id}/links` | AssetLinkRequest | 201 | Link asset to a requirement, control, or other entity |
| GET | `/assets/{id}/links?target_type=` |—| 200 | List links (optional target type filter) |
| DELETE | `/assets/{id}/links/{linkId}` |—| 204 | Delete link |
| GET | `/assets/links/by-target?target_type=&target_identifier=&project=` |—| 200 | Reverse lookup: find assets linked to a target |

**AssetLinkRequest:**

```json
{
  "targetType": "REQUIREMENT",
  "targetIdentifier": "GC-M010",
  "linkType": "IMPLEMENTS",
  "targetUrl": "https://example.com/req/GC-M010",
  "targetTitle": "Operational Asset Entity"
}
```

Target types: `REQUIREMENT`, `CONTROL`, `RISK_SCENARIO`, `THREAT_MODEL_ENTRY`, `FINDING`, `EVIDENCE`, `AUDIT`, `EXTERNAL`

Link types: `IMPLEMENTS`, `MITIGATES`, `SUBJECT_OF`, `EVIDENCED_BY`, `GOVERNED_BY`, `DEPENDS_ON`, `ASSOCIATED`

### Asset Topology

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/assets/topology/cycles?project=` |—| 200 | Detect cycles in asset graph |
| GET | `/assets/{id}/topology/impact` |—| 200 | Multi-hop impact analysis |
| POST | `/assets/topology/subgraph?project=` | SubgraphRequest | 200 | Extract connected subgraph |

**SubgraphRequest:**

```json
{
  "rootUids": ["ASSET-001", "ASSET-002"]
}
```

**AssetSubgraphResponse:**

```json
{
  "assets": [{ "id": "uuid", "uid": "ASSET-001", "name": "...", "..." : "..." }],
  "relations": [{ "id": "uuid", "sourceUid": "ASSET-001", "targetUid": "ASSET-002", "relationType": "DEPENDS_ON" }]
}
```

### Observations

Time-bounded state facts about an asset. Each observation records a discrete
observed value at a point in time; the `/observations/latest` projection surfaces
the most recent non-expired observation per key.

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/assets/{id}/observations?project=` | ObservationRequest | 201 | Create observation |
| GET | `/assets/{id}/observations?project=` |—| 200 | List observations |
| GET | `/assets/{id}/observations/{obsId}` |—| 200 | Get observation by UUID |
| PUT | `/assets/{id}/observations/{obsId}` | UpdateObservationRequest | 200 | Update mutable fields |
| DELETE | `/assets/{id}/observations/{obsId}` |—| 204 | Delete observation |
| GET | `/assets/{id}/observations/latest?project=` |—| 200 | Latest non-expired observation per key |

**ObservationRequest** (`category`, `observationKey`, `observationValue`, `source`, `observedAt` are required):

```json
{
  "category": "CONFIGURATION",
  "observationKey": "cis.1.1.patch_state",
  "observationValue": "compliant",
  "source": "vuln-scanner-v2",
  "observedAt": "2026-05-01T12:00:00Z",
  "expiresAt": "2026-11-01T12:00:00Z",
  "confidence": "0.95",
  "evidenceRef": "EVD-0042"
}
```

**UpdateObservationRequest** (all fields optional):

```json
{
  "observationValue": "non-compliant",
  "expiresAt": "2026-08-01T12:00:00Z",
  "confidence": "0.80",
  "evidenceRef": "EVD-0043"
}
```

Observation categories: `CONFIGURATION`, `EXPOSURE`, `IDENTITY`, `DEPLOYMENT`, `PATCH_STATE`, `RELATIONSHIP`, `OTHER`

MCP surface: `gc_observation` with actions `create`, `update`, `delete`, `latest`. Snake_case MCP args map to camelCase DTO fields via the adapter's `TO_CAMEL` table—`observation_key` → `observationKey`, `observation_value` → `observationValue`, `observed_at` → `observedAt`, `expires_at` → `expiresAt`, `evidence_ref` → `evidenceRef`. The old field names (`title`, `statement`, `valid_until`, `metadata`) were removed in GC-L008.

### Quality Gates

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/quality-gates?project=` | QualityGateRequest | 201 | Create quality gate |
| GET | `/quality-gates?project=` |—| 200 | List quality gates |
| GET | `/quality-gates/{id}` |—| 200 | Get quality gate |
| PUT | `/quality-gates/{id}` | UpdateQualityGateRequest | 200 | Update quality gate |
| DELETE | `/quality-gates/{id}` |—| 204 | Delete quality gate |
| POST | `/quality-gates/evaluate?project=` |—| 200 | Evaluate all enabled gates (CI/CD) |

**QualityGateRequest:**

```json
{
  "name": "Test Coverage Gate",
  "description": "Minimum 80% of ACTIVE requirements must have TESTS link",
  "metricType": "COVERAGE",
  "metricParam": "TESTS",
  "scopeStatus": "ACTIVE",
  "operator": "GTE",
  "threshold": 80.0
}
```

- `metricType`: `COVERAGE` (% with link type), `ORPHAN_COUNT`, `COMPLETENESS` (issue count)
- `metricParam`: Required for `COVERAGE`—a LinkType (`IMPLEMENTS`, `TESTS`, `DOCUMENTS`, `CONSTRAINS`, `VERIFIES`)
- `scopeStatus`: Filter requirements by status. Omit to check all non-archived
- `operator`: `GTE` (>=), `LTE` (<=), `EQ` (==), `GT` (>), `LT` (<)

**QualityGateEvaluationResponse** (`POST /quality-gates/evaluate`):

```json
{
  "projectIdentifier": "ground-control",
  "timestamp": "2026-03-24T06:00:00Z",
  "passed": false,
  "totalGates": 2,
  "passedCount": 1,
  "failedCount": 1,
  "gates": [
    {
      "gateId": "uuid",
      "gateName": "Test Coverage Gate",
      "metricType": "COVERAGE",
      "metricParam": "TESTS",
      "scopeStatus": "ACTIVE",
      "operator": "GTE",
      "threshold": 80.0,
      "actualValue": 65.0,
      "passed": false
    }
  ]
}
```

### Graph

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/admin/graph/materialize` |—| 200 | Materialize graph (AGE) |
| GET | `/graph/visualization?project=&entityTypes=` |—| 200 | Mixed-entity graph projection |
| POST | `/graph/subgraph/query?project=` | GraphNeighborhoodQueryRequest | 200 | Mixed-entity subgraph around root graph node IDs |
| POST | `/graph/traversal/query?project=` | GraphNeighborhoodQueryRequest | 200 | Mixed-entity bounded traversal from root graph node IDs |
| POST | `/graph/paths/query?project=` | GraphPathsQueryRequest | 200 | Mixed-entity path between two graph node IDs |
| GET | `/requirements/graph/ancestors/{uid}?project=&depth=N` |—| 200 | Requirement-only ancestor UIDs |
| GET | `/requirements/graph/descendants/{uid}?project=&depth=N` |—| 200 | Requirement-only descendant UIDs |
| GET | `/requirements/graph/paths?project=&source=&target=` |—| 200 | Requirement-only paths by UID |

`project` is required on graph routes. `entityTypes` is an optional repeated
query parameter on visualization, for example
`entityTypes=REQUIREMENT&entityTypes=OPERATIONAL_ASSET`. Query request bodies
use graph node IDs of the form `GraphEntityType:UUID`, not requirement UIDs.
When omitted, `entityTypes` means all entity types. Filtering prunes both nodes
and edges.

**GraphNeighborhoodQueryRequest:**

```json
{
  "rootNodeIds": ["REQUIREMENT:00000000-0000-0000-0000-000000000001"],
  "maxDepth": 2,
  "entityTypes": ["REQUIREMENT", "OPERATIONAL_ASSET"]
}
```

**GraphPathsQueryRequest:**

```json
{
  "sourceNodeId": "REQUIREMENT:00000000-0000-0000-0000-000000000001",
  "targetNodeId": "OPERATIONAL_ASSET:00000000-0000-0000-0000-000000000002",
  "maxDepth": 4,
  "entityTypes": ["REQUIREMENT", "OPERATIONAL_ASSET"]
}
```

**Graph visualization / subgraph response shape:**

```json
{
  "nodes": [
    {
      "id": "REQUIREMENT:00000000-0000-0000-0000-000000000001",
      "domainId": "00000000-0000-0000-0000-000000000001",
      "entityType": "REQUIREMENT",
      "projectIdentifier": "ground-control",
      "uid": "GC-A001",
      "label": "GC-A001",
      "properties": { "title": "Example", "status": "ACTIVE" }
    },
    {
      "id": "OPERATIONAL_ASSET:00000000-0000-0000-0000-000000000002",
      "domainId": "00000000-0000-0000-0000-000000000002",
      "entityType": "OPERATIONAL_ASSET",
      "projectIdentifier": "ground-control",
      "uid": "ASSET-001",
      "label": "Asset 001",
      "properties": { "assetType": "SERVICE" }
    }
  ],
  "edges": [
    {
      "id": "edge-1",
      "edgeType": "ASSOCIATED",
      "sourceId": "REQUIREMENT:00000000-0000-0000-0000-000000000001",
      "targetId": "OPERATIONAL_ASSET:00000000-0000-0000-0000-000000000002",
      "sourceEntityType": "REQUIREMENT",
      "targetEntityType": "OPERATIONAL_ASSET",
      "properties": {}
    }
  ],
  "totalNodes": 2,
  "totalEdges": 1,
  "rootNodeIds": ["REQUIREMENT:00000000-0000-0000-0000-000000000001"]
}
```

Visualization responses omit `rootNodeIds`. Subgraph and traversal responses
include it.

**Mixed graph path response shape:**

```json
[
  {
    "nodeIds": [
      "REQUIREMENT:00000000-0000-0000-0000-000000000001",
      "OPERATIONAL_ASSET:00000000-0000-0000-0000-000000000002"
    ],
    "edgeTypes": ["ASSOCIATED"]
  }
]
```

### GitHub Issues

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/admin/github/issues` | GitHubIssueRequest | 201 | Create issue from requirement |

**GitHubIssueRequest:**

```json
{
  "requirementUid": "GC-A001",
  "repo": "owner/repo",
  "extraBody": "Additional markdown (optional)",
  "labels": ["enhancement"]
}
```

**GitHubIssueResponse:**

```json
{
  "issueUrl": "https://github.com/owner/repo/issues/42",
  "issueNumber": 42,
  "traceabilityLinkId": "uuid",
  "warning": null
}
```

### Export

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/export/requirements?project=&format=csv` |—| 200 | Export requirements as CSV, Excel, or PDF |
| POST | `/export/sweep?project=&format=csv` |—| 200 | Run sweep and export as CSV, Excel, or PDF |
| GET | `/export/document/{documentId}?format=sdoc` |—| 200 | Export document (sdoc, html, pdf, or reqif) |

The `format` query parameter accepts `csv` (default), `xlsx`, or `pdf`. Responses include
`Content-Disposition: attachment` headers with a generated filename.

Content types by format:
- `csv`: `text/csv`
- `xlsx`: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `pdf`: `application/pdf`

**Requirements export** includes: UID, title, statement, rationale, type, priority,
status, wave, traceability links, timestamps. Excel format adds a second "Traceability"
sheet with the full link matrix.

**Sweep export** includes: summary, cycles, orphans, coverage gaps, cross-wave violations,
consistency violations, completeness, and quality gate results. Excel format uses one
sheet per analysis category.

### Import / Sync

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/admin/import/strictdoc` | multipart/form-data | 200 | Import .sdoc file |
| POST | `/admin/import/reqif` | multipart/form-data | 200 | Import .reqif file |
| POST | `/admin/sync/github?owner=X&repo=Y` |—| 200 | Sync GitHub issues |
| POST | `/admin/sync/github/prs?owner=X&repo=Y` |—| 200 | Sync GitHub pull requests |

StrictDoc import creates requirements, relations, traceability links, and preserves the
document structure (document, sections, text blocks). The response includes all counters:
`requirementsParsed`, `requirementsCreated`, `requirementsUpdated`, `relationsCreated`,
`relationsSkipped`, `traceabilityLinksCreated`, `traceabilityLinksSkipped`,
`documentsCreated`, `sectionsCreated`, `sectionContentsCreated`, `errors`.

### Verification Results

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/verification-results` | VerificationResultRequest | 201 | Create verification result |
| GET | `/verification-results` |—| 200 | List verification results |
| GET | `/verification-results/{id}` |—| 200 | Get verification result by UUID |
| PUT | `/verification-results/{id}` | UpdateVerificationResultRequest | 200 | Update verification result |
| DELETE | `/verification-results/{id}` |—| 204 | Delete verification result |

All endpoints accept an optional `project` query parameter.

**Filters on GET list:**
- `requirement_id` (UUID)—filter by requirement
- `prover` (string)—filter by verifier tool identifier
- `result` (enum)—PROVEN, REFUTED, TIMEOUT, UNKNOWN, ERROR

**VerificationResultRequest fields:** `prover` (required), `result` (required),
`assuranceLevel` (required, L0-L3), `verifiedAt` (required, ISO 8601), `targetId`
(optional, traceability link UUID), `requirementId` (optional), `property` (optional),
`evidence` (optional, JSON object), `expiresAt` (optional, ISO 8601).

### Plugins

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/plugins` |—| 200 | List all registered plugins |
| GET | `/plugins/{name}` |—| 200 | Get plugin by name |
| POST | `/plugins` | RegisterPluginRequest | 201 | Register a dynamic plugin |
| DELETE | `/plugins/{name}` |—| 204 | Unregister a dynamic plugin |

All endpoints accept an optional `project` query parameter.

**Filters on GET list:**
- `type` (enum)—PACK_HANDLER, REGISTRY_BACKEND, VALIDATOR, POLICY_HOOK, VERIFIER, EMBEDDING_PROVIDER, GRAPH_CONTRIBUTOR, CUSTOM
- `capability` (string)—filter by capability tag
- `project` (string)—filter dynamic plugins by project

**RegisterPluginRequest fields:** `name` (required, max 100), `version` (required, max 50),
`type` (required, PluginType enum), `description` (optional), `capabilities` (optional, string set),
`metadata` (optional, JSON object).

### Control Packs

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/control-packs` |—| 200 | List installed packs |
| GET | `/control-packs/{packId}` |—| 200 | Get pack by identifier |
| PUT | `/control-packs/{packId}/deprecate` |—| 200 | Deprecate a pack |
| DELETE | `/control-packs/{packId}` |—| 204 | Remove a pack |
| GET | `/control-packs/{packId}/entries` |—| 200 | List pack entries |
| GET | `/control-packs/{packId}/entries/{entryUid}` |—| 200 | Get a pack entry |
| POST | `/control-packs/{packId}/entries/{entryUid}/overrides` | CreateControlPackOverrideRequest | 201 | Create field override |
| GET | `/control-packs/{packId}/entries/{entryUid}/overrides` |—| 200 | List overrides |
| DELETE | `/control-packs/{packId}/entries/{entryUid}/overrides/{id}` |—| 204 | Delete override |

All endpoints accept an optional `project` query parameter.

Control-pack installation and upgrade are registry-backed operations only. Register
or import a `CONTROL_PACK` in `/pack-registry`, then use `/pack-install-records/install`
or `/pack-install-records/upgrade` so resolution, trust evaluation, and audit recording
cannot be bypassed.

**CreateControlPackOverrideRequest fields:** `fieldName` (required—title, description, objective,
controlFunction, owner, implementationScope, or category), `overrideValue` (optional; title
must be non-blank), `reason` (optional, max 500).

**Lifecycle states:** INSTALLED → UPGRADED → DEPRECATED → REMOVED.

### Threat Models

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/threat-models` | ThreatModelRequest | 201 | Create threat model entry |
| GET | `/threat-models` |—| 200 | List threat models for a project |
| GET | `/threat-models/{id}` |—| 200 | Get threat model by UUID |
| GET | `/threat-models/uid/{uid}` |—| 200 | Get threat model by UID |
| PUT | `/threat-models/{id}` | UpdateThreatModelRequest | 200 | Update mutable fields |
| DELETE | `/threat-models/{id}` |—| 204 | Delete threat model (cascades to links) |
| PUT | `/threat-models/{id}/status` | `{"status": "ACTIVE"}` | 200 | Transition lifecycle status |
| GET | `/threat-models/{id}/requirements` |—| 200 | List requirements linked to a threat model |
| GET | `/threat-models/{id}/trace` |—| 200 | End-to-end security trace: assets, controls, requirements, and per-requirement implementing artifacts |
| GET | `/threat-models/workspace` |—| 200 | Read-only workspace: scoped assets, flows, threat entries with linked controls/requirements and staleness indicators (GC-Q010) |
| POST | `/threat-models/{id}/links` | ThreatModelLinkRequest | 201 | Create threat-model link |
| GET | `/threat-models/{id}/links` |—| 200 | List links for a threat model |
| DELETE | `/threat-models/{id}/links/{linkId}` |—| 204 | Delete threat-model link |

All endpoints accept an optional `project` query parameter. When omitted, the request
auto-resolves to the single project in single-project deployments. In multi-project
deployments the parameter is required and the request returns 422 `project_required`
if absent.

Threat models are a separate aggregate from risk scenarios per ADR-024. They capture
upstream security analysis (source, event, effect) and do not carry quantified risk,
treatment, or governance state.

`GET /{id}/trace` returns a `SecurityTraceResponse` with `sourceType` (`THREAT_MODEL`), `sourceId`, `sourceUid`,
`sourceTitle`, `assets[]`, `controls[]`, and `requirements[]`. Each requirement entry carries `requirement`
(full requirement record) and `artifacts[]` (the `TraceabilityLink` rows recording implementing code, PRs,
issues, and controls). Unknown `id` → 404 `not_found`.

`DELETE /threat-models/{id}` is rejected with 409 `threat_model_referenced` while any
`AssetLink` (`THREAT_MODEL_ENTRY` target) or `RiskScenarioLink` (`THREAT_MODEL` target)
still references the threat model. The conflict envelope's `detail` block lists the
referencing asset and scenario UIDs so callers can clean them up before retrying.

**ThreatModelRequest fields:** `uid` (required, max 30), `title` (required, max 200),
`threatSource` (required), `threatEvent` (required), `effect` (required), `stride`
(optional, STRIDE enum: SPOOFING, TAMPERING, REPUDIATION, INFORMATION_DISCLOSURE,
DENIAL_OF_SERVICE, ELEVATION_OF_PRIVILEGE), `narrative` (optional analyst context,
non-authoritative).

**UpdateThreatModelRequest fields:** `title`, `threatSource`, `threatEvent`, `effect`,
`stride`, `narrative`, `clearStride` (boolean), `clearNarrative` (boolean). Only fields
present in the request body are updated. Required fields (`title`, `threatSource`,
`threatEvent`, `effect`) reject blank strings server-side with 422 `validation_error`
when present. Optional fields (`stride`, `narrative`) cannot be cleared by sending
`null` (which means "no change")—set `clearStride` or `clearNarrative` to `true` to
explicitly null them. When a `clear*` flag is true, any value supplied in the
corresponding field is ignored.

**ThreatModelLinkRequest fields:** `targetType` (required, ThreatModelLinkTargetType
enum), `targetEntityId` (UUID, for internal first-class targets), `targetIdentifier`
(string max 500, for external / not-yet-modeled targets), `linkType` (required,
ThreatModelLinkType enum), `targetUrl` (optional, max 2000), `targetTitle` (optional,
max 255).

**Internal target types (require `targetEntityId`, resolved project-scoped):** ASSET
(includes boundaries via `AssetType.BOUNDARY`), REQUIREMENT, CONTROL, RISK_SCENARIO,
OBSERVATION, RISK_ASSESSMENT_RESULT, VERIFICATION_RESULT, FINDING (per GC-H009: governed vulnerability/scan/pentest finding records), EVIDENCE (per GC-L006 / ADR-045
projection alignment—`targetEntityId` must reference an `EvidenceArtifact` UUID
returned by `POST /api/v1/evidence-artifacts`).

**External target types (require `targetIdentifier`):** ARCHITECTURE_MODEL (for example, C4
source or Structurizr DSL, per ADR-011), CODE (repo-relative path), ISSUE (GitHub
issue or PR number), EXTERNAL (catch-all that also covers CVE identifiers, scanner
finding IDs, and pentest report IDs that have not been ingested as first-class
`Finding` records).

**Link types:** AFFECTS (threat affects an asset or boundary), EXPLOITS (threat
exploits a requirement or condition), MITIGATED_BY (threat is mitigated by a control),
ASSESSED_IN (threat feeds a risk scenario or assessment), OBSERVED_IN (threat
evidenced by an observation, verification, or vulnerability finding), DOCUMENTED_IN
(threat documented in an architecture model, code, or issue), ASSOCIATED (generic
association).

**Lifecycle states:** DRAFT → ACTIVE → ARCHIVED (and DRAFT → ARCHIVED directly).

### Risk Scenarios

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/risk-scenarios` | RiskScenarioRequest | 201 | Create risk scenario |
| GET | `/risk-scenarios` |—| 200 | List risk scenarios for a project |
| GET | `/risk-scenarios/{id}` |—| 200 | Get risk scenario by UUID |
| GET | `/risk-scenarios/uid/{uid}` |—| 200 | Get risk scenario by UID |
| PUT | `/risk-scenarios/{id}` | UpdateRiskScenarioRequest | 200 | Update mutable fields |
| DELETE | `/risk-scenarios/{id}` |—| 204 | Delete risk scenario |
| PUT | `/risk-scenarios/{id}/status` | `{"status": "ACTIVE"}` | 200 | Transition lifecycle status |
| GET | `/risk-scenarios/{id}/requirements` |—| 200 | List requirements linked to a risk scenario |
| GET | `/risk-scenarios/{id}/trace` |—| 200 | End-to-end security trace: assets, controls, requirements, and per-requirement implementing artifacts |
| GET | `/risk-scenarios/workspace` |—| 200 | Read-only workspace: risk scenarios with linked assets, controls, findings, evidence, assessments, treatments, and register memberships; explicit-signal review indicator (GC-Q009) |
| POST | `/risk-scenarios/{id}/links` | RiskScenarioLinkRequest | 201 | Create risk-scenario link |
| GET | `/risk-scenarios/{id}/links` |—| 200 | List links for a risk scenario |
| DELETE | `/risk-scenarios/{id}/links/{linkId}` |—| 204 | Delete risk-scenario link |

All endpoints accept an optional `project` query parameter (required in multi-project deployments).

**RiskScenarioRequest fields (FAIR-CRST scoping axes):** `uid` (required, max 20), `title` (required, max 200), `threat` (required, min 10, no max), `method` (required, min 10, no max), `asset` (required, min 10, no max), `effect` (required, min 10, no max), `timeHorizon` (required, max 100). The `vulnerability` field has been removed.

**UpdateRiskScenarioRequest fields:** `title`, `threat`, `method`, `asset`, `effect`, `timeHorizon`, all optional (partial update). Required fields (`threat`, `method`, `asset`, `effect`) reject blank strings when present.

**RiskScenarioResponse fields:** all request fields (minus validation constraints), plus `id`, `graphNodeId`, `projectIdentifier`, `status`, `fairSentence` (derived, never stored), `createdAt`, `updatedAt`, `createdBy`. The `fairSentence` field renders `"{threat} impacts {asset} via {method}, causing {effect}"` and is computed on every response.

`GET /{id}/trace` returns a `SecurityTraceResponse` with `sourceType` (`RISK_SCENARIO`), `sourceId`, `sourceUid`,
`sourceTitle`, `assets[]`, `controls[]`, and `requirements[]`. Each requirement entry carries `requirement`
(full requirement record) and `artifacts[]` (the `TraceabilityLink` rows recording implementing code, PRs,
issues, and controls). Unknown `id` → 404 `not_found`.

**Lifecycle states:** DRAFT → ACTIVE → ARCHIVED (and DRAFT → ARCHIVED directly).

### Methodology Profiles

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/methodology-profiles` | MethodologyProfileRequest | 201 | Create methodology profile |
| GET | `/methodology-profiles` |—| 200 | List methodology profiles for a project (auto-seeds defaults on first read) |
| GET | `/methodology-profiles/{id}` |—| 200 | Get methodology profile by UUID |
| PUT | `/methodology-profiles/{id}` | UpdateMethodologyProfileRequest | 200 | Update mutable fields |
| DELETE | `/methodology-profiles/{id}` |—| 204 | Delete methodology profile |

All endpoints accept an optional `project` query parameter (required in multi-project deployments).

**MethodologyProfileRequest fields:** `profileKey` (required, max 100), `name` (required, max 200), `version` (required, max 50), `family` (required, enum: FAIR, NIST_SP800_30_R1, ISO_27005, CUSTOM), `description` (optional), `inputSchema` (optional JSON object: methodology assessment input vocabulary), `outputSchema` (optional JSON object: methodology assessment output vocabulary), `treatmentStrategyVocabulary` (optional JSON object; strategy vocabulary keyed by stable strategy key, with the value object profile or pack defined and carrying display labels, semantics, or other metadata), `status` (optional, enum: ACTIVE, DEPRECATED; defaults to ACTIVE), `crosswalkEntries` (optional list of `CrosswalkEntry` objects; see below).

`UpdateMethodologyProfileRequest` carries the same field set minus `profileKey`; null fields are left unchanged. A null `crosswalkEntries` leaves the existing list intact; an empty list clears it; a non-null list replaces it in full.

**CrosswalkEntry fields (GC-T012):** `normalizedConcept` (required, enum: `THREAT_SOURCE`, `THREAT_EVENT`, `VULNERABILITY_OR_EXPOSURE`, `ASSET`, `PROCESS_OR_OBJECTIVE`, `CONSEQUENCE_OR_EFFECT`, `CONTROL`, `LIKELIHOOD_OR_FREQUENCY`, `IMPACT_OR_LOSS_MAGNITUDE`, `TREATMENT`), `vocabularySurface` (required, enum: `INPUT_SCHEMA`, `OUTPUT_SCHEMA`, `TREATMENT_STRATEGY_VOCABULARY`), `sourceFieldPath` (required, max 400; dotted path into the named surface's schema properties), `sourceTermLabel` (optional, max 200), `sourceTermDefinition` (optional, max 2000), `scale` (optional, max 100), `units` (optional, max 100), `conversionRule` (optional, max 400; requires `scale` or `units` to be set), `limitations` (optional, max 400).

Semantic validation: duplicate `(normalizedConcept, vocabularySurface, sourceFieldPath)` tuples within the same profile → 422 `duplicate_crosswalk_entry`; surface referenced but corresponding schema is null → 422 `crosswalk_surface_not_present`; `sourceFieldPath` not found in the surface schema's properties → 422 `crosswalk_unknown_field_path`; `conversionRule` set with both `scale` and `units` null → 422 `crosswalk_conversion_rule_missing_scale_or_units`.

**Example `crosswalkEntries` payload:**
```json
[
  {
    "normalizedConcept": "LIKELIHOOD_OR_FREQUENCY",
    "vocabularySurface": "INPUT_SCHEMA",
    "sourceFieldPath": "loss_event_frequency",
    "sourceTermLabel": "Loss Event Frequency",
    "scale": "continuous",
    "units": "annual events",
    "conversionRule": "LEF = TEF × Vulnerability"
  },
  {
    "normalizedConcept": "IMPACT_OR_LOSS_MAGNITUDE",
    "vocabularySurface": "INPUT_SCHEMA",
    "sourceFieldPath": "primary_loss_magnitude",
    "sourceTermLabel": "Primary Loss Magnitude",
    "scale": "continuous",
    "units": "monetary"
  }
]
```

The seeded profiles (`FAIR_V3_0`, `NIST_SP800_30_R1`, `ISO_27005_V2022`) ship with starter crosswalk entries pre-populated on first project list.

`(project_id, profile_key, version)` is unique. Conflict on duplicate create returns 409 `conflict`.

### Treatment Plans

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/treatment-plans` | TreatmentPlanRequest | 201 | Create treatment plan |
| GET | `/treatment-plans` |—| 200 | List treatment plans for a project (optional `riskRegisterRecordId` filter) |
| GET | `/treatment-plans/{id}` |—| 200 | Get treatment plan by UUID |
| PUT | `/treatment-plans/{id}` | UpdateTreatmentPlanRequest | 200 | Update mutable fields |
| PUT | `/treatment-plans/{id}/status` | `{"status": "IN_PROGRESS"}` | 200 | Transition lifecycle status |
| DELETE | `/treatment-plans/{id}` |—| 204 | Delete treatment plan |

All endpoints accept an optional `project` query parameter (required in multi-project deployments).

**TreatmentPlanRequest fields:** `uid` (required, max 50, unique per project), `title` (required, max 200), `riskRegisterRecordId` (required, UUID), `riskScenarioId` (optional, UUID; must belong to the linked register record's scenarios), `strategy` (required, enum: MITIGATE, ACCEPT, TRANSFER, SHARE, AVOID, OTHER), `methodologyProfileId` (optional, UUID; required when `strategy = OTHER`), `methodologyStrategyKey` (optional, max 100; required when `strategy = OTHER`, must exist in the resolved profile's `treatmentStrategyVocabulary`), `owner` (optional, max 200), `rationale` (optional), `dueDate` (optional, ISO-8601 instant), `status` (optional, defaults to PLANNED), `actionItems` (optional list of typed action items: each requires `owner` [max 200], `dueDate` [ISO-8601 instant], `status` [enum PLANNED/IN_PROGRESS/BLOCKED/DONE/CANCELED]; optional `assignee` [max 200] and `description` [max 4000]), `reassessmentTriggers` (optional list of typed triggers), `riskAssessmentResultId` (GC-T015, optional UUID; same-project lookup through `GraphTargetResolverService`), `monitoredRiskFactors` (GC-T015, optional list of `{label, category, cadence?, notes?}` entries), `updateCadence` (GC-T015, optional ISO-8601 duration like `P30D`).

`UpdateTreatmentPlanRequest` carries the mutable subset; null fields are left unchanged.

**Methodology binding (GC-T004 / C5):** when the resulting `strategy` is `OTHER`, the request must resolve a `methodologyProfileId` (same-project lookup; cross-project or non-existent → 404 `not_found`) and a `methodologyStrategyKey` that exists in that profile's `treatmentStrategyVocabulary` (missing/blank/non-member → 400 `validation_error`). When the resulting strategy is one of the canonical five, the service silently clears any stored profile/key pair—supplied methodology fields are ignored rather than rejected.

**Lifecycle states:** PLANNED → IN_PROGRESS → {BLOCKED, COMPLETED, CANCELED}; BLOCKED → IN_PROGRESS or CANCELED; PLANNED → CANCELED. COMPLETED and CANCELED are terminal.

### Risk Appetite Profiles (GC-T005)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/risk-appetite-profiles` | RiskAppetiteProfileRequest | 201 | Create versioned appetite profile |
| GET | `/risk-appetite-profiles` |—| 200 | List appetite profiles for a project |
| GET | `/risk-appetite-profiles/{id}` |—| 200 | Get appetite profile by UUID |
| PUT | `/risk-appetite-profiles/{id}` | UpdateRiskAppetiteProfileRequest | 200 | Update mutable fields |
| DELETE | `/risk-appetite-profiles/{id}` |—| 204 | Delete appetite profile |

Versioned by `(project, profileKey, version)`. Setting `active=true` archives any other active version under the same `profileKey` in the project. Tolerance bands are typed by `AppetiteToleranceKind` (QUALITATIVE, MONETARY_RANGE, LOSS_EVENT_FREQUENCY, EXCEEDANCE_PROBABILITY, COMPOSITE) and consumed by `RiskAppetiteEvaluator` for downstream campaign / KRI / posture analytics.

**RiskAppetiteProfileRequest fields:** `profileKey` (required, max 100), `name` (required, max 200), `version` (required, max 50), `appetiteStatement` (optional), `owner` (optional, max 200), `active` (optional, default true), `tolerances` (optional list of `{category, kind, qualitativeLabel?, monetaryLow?, monetaryHigh?, currency?, lossEventFrequencyMax?, exceedanceProbabilityMax?, criteria?, rationale?}` entries; duplicate `(category, kind)` tuples are rejected with 422 `validation_error`).

### Risk Assessment Campaigns (GC-T006)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/risk-assessment-campaigns` | RiskAssessmentCampaignRequest | 201 | Create campaign |
| GET | `/risk-assessment-campaigns` |—| 200 | List campaigns for a project |
| GET | `/risk-assessment-campaigns/{id}` |—| 200 | Get campaign by UUID |
| PUT | `/risk-assessment-campaigns/{id}` | UpdateRiskAssessmentCampaignRequest | 200 | Update mutable fields |
| PUT | `/risk-assessment-campaigns/{id}/phase` | `{"phase": "IDENTIFICATION"}` | 200 | Advance the campaign phase |
| DELETE | `/risk-assessment-campaigns/{id}` |—| 204 | Delete campaign |

Campaign phases: PLANNING → IDENTIFICATION → ANALYSIS → EVALUATION → TREATMENT → CLOSED. Each phase may also cancel to CLOSED. Reaching EVALUATION (or later) requires a bound `methodologyProfileId`; the methodology binding is immutable from EVALUATION through CLOSED so the audit trail stays unambiguous.

**RiskAssessmentCampaignRequest fields:** `uid` (required, max 50), `title` (required, max 200), `owner` (optional, max 200), `objective` (optional), `methodologyProfileId` (optional UUID), `appetiteProfileId` (optional UUID; used in the EVALUATION phase via `RiskAppetiteEvaluator`), `scheduledStart`/`scheduledEnd` (optional ISO-8601 instants), `scope` and `approvalMetadata` (optional structured maps), `scopedAssetIds` (optional list of asset identifiers).

### Key Risk Indicators (GC-T007)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/key-risk-indicators` | KeyRiskIndicatorRequest | 201 | Create KRI |
| GET | `/key-risk-indicators` |—| 200 | List KRIs for a project |
| GET | `/key-risk-indicators/{id}` |—| 200 | Get KRI by UUID |
| PUT | `/key-risk-indicators/{id}` | UpdateKeyRiskIndicatorRequest | 200 | Update mutable fields |
| POST | `/key-risk-indicators/{id}/measurements` | `{"value": 45, "measuredAt": "..."}` | 200 | Record a measurement, returns the updated KRI with reclassified band |
| DELETE | `/key-risk-indicators/{id}` |—| 204 | Delete KRI |

Two-breakpoint classification: `direction=HIGHER_IS_WORSE` (default) makes values ≥ `yellowThreshold` YELLOW and ≥ `redThreshold` RED; `direction=LOWER_IS_WORSE` inverts the inequalities. A measurement that crosses from a non-RED band into RED publishes a synchronous `KriBreachedEvent`; `ReassessmentSignalService` fans the `KRI_BREACH` reassessment signal out to assessments under the KRI's linked register record / scenario.

**KeyRiskIndicatorRequest fields:** `uid` (required, max 50), `name` (required, max 200), `description` (optional), `metricUnit` (optional, max 50), `yellowThreshold` / `redThreshold` (numeric, required before a measurement can be recorded), `direction` (optional, default `HIGHER_IS_WORSE`), `owner` (optional, max 200), `riskRegisterRecordId` / `riskScenarioId` (optional same-project UUIDs).

### Findings

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/findings` | FindingRequest | 201 | Create finding |
| GET | `/findings` |—| 200 | List findings for a project |
| GET | `/findings/{id}` |—| 200 | Get finding by UUID |
| GET | `/findings/uid/{uid}` |—| 200 | Get finding by UID |
| PUT | `/findings/{id}` | UpdateFindingRequest | 200 | Update mutable fields |
| DELETE | `/findings/{id}` |—| 204 | Delete finding (cascades to links) |
| PUT | `/findings/{id}/status` | `{"status": "REMEDIATION_IN_PROGRESS"}` | 200 | Transition lifecycle status |
| POST | `/findings/{id}/links` | FindingLinkRequest | 201 | Create finding link |
| GET | `/findings/{id}/links` |—| 200 | List links for a finding |
| DELETE | `/findings/{id}/links/{linkId}` |—| 204 | Delete finding link |

All endpoints accept an optional `project` query parameter (same semantics as the
Threat Model endpoints above).

Findings are a separate aggregate from observations, controls, and the risk-management
cluster per ADR-038. They capture governed GRC issues (audit findings, control
deficiencies, policy violations, vulnerabilities, exception escalations) and own the
remediation lifecycle. Affected controls, risks, assets, observations, evidence,
audits, and remediation plans are represented as outbound `FindingLink` edges.

`DELETE /findings/{id}` is rejected with 409 `finding_referenced` while any
`AssetLink` (`FINDING` target), `ControlLink` (`FINDING` target), or `RiskScenarioLink`
(`FINDING` target) still references the finding by `targetEntityId`. The conflict
envelope's `detail` block lists the referencing asset, control, and scenario UIDs so
callers can clean them up before retrying.

**FindingRequest fields:** `uid` (required, max 30), `title` (required, max 200),
`findingType` (required, enum: AUDIT_FINDING, CONTROL_DEFICIENCY, POLICY_VIOLATION,
VULNERABILITY, EXCEPTION_ESCALATION), `severity` (required, enum: CRITICAL, HIGH,
MEDIUM, LOW, INFORMATIONAL), `description` (required), `rootCauseAnalysis` (optional),
`owner` (optional, max 100), `dueDate` (optional, ISO-8601 date).

**UpdateFindingRequest fields:** `title`, `findingType`, `severity`, `description`,
`rootCauseAnalysis`, `owner`, `dueDate`, `clearRootCauseAnalysis` (boolean),
`clearOwner` (boolean), `clearDueDate` (boolean). Only fields present in the request
body are updated. Required fields (`title`, `description`) reject blank strings
server-side with 422 `validation_error` when present. Optional fields cannot be
cleared by sending `null` (which means "no change")—set the corresponding `clear*`
flag to `true` to explicitly null them. When a `clear*` flag is true, any value
supplied in the corresponding field is ignored.

**FindingLinkRequest fields:** `targetType` (required, FindingLinkTargetType enum),
`targetEntityId` (UUID, for internal first-class targets), `targetIdentifier` (string
max 500, for external / not-yet-modeled targets), `linkType` (required,
FindingLinkType enum), `targetUrl` (optional, max 2000), `targetTitle` (optional, max
255).

**Internal target types (require `targetEntityId`, resolved project-scoped):**
CONTROL, RISK_SCENARIO, ASSET, OBSERVATION, AUDIT (promoted from external placeholder
in GC-U001 / ADR-047; `targetEntityId` must reference a UUID returned by
`POST /api/v1/audits`), EVIDENCE (per GC-L006 / ADR-045 projection alignment;
`targetEntityId` must reference an `EvidenceArtifact` UUID returned by
`POST /api/v1/evidence-artifacts`).

**External target types (require `targetIdentifier`):** OPERATIONAL_ARTIFACT (generic
artifact reference, per ADR-011), REMEDIATION_PLAN (remediation plan identifier),
EXTERNAL (catch-all).

**Link types:** AFFECTS (finding affects an entity), CAUSED_BY (finding is caused by
the linked entity), MITIGATED_BY (finding is mitigated by a control or plan),
EVIDENCED_BY (finding is evidenced by a referenced artifact), OBSERVED_IN (finding
was observed in an audit, observation, or evidence record), REMEDIATED_BY (finding is
remediated by a plan or control), ASSOCIATED (generic association).

**Lifecycle states:** OPEN → REMEDIATION_IN_PROGRESS → REMEDIATION_COMPLETE →
VERIFIED_CLOSED. `REMEDIATION_COMPLETE` can transition back to
`REMEDIATION_IN_PROGRESS` when verification rejects the claimed remediation.
`VERIFIED_CLOSED` is terminal—reopening a verified-closed finding creates a new
finding rather than reanimating the closed record.

### Audits (GC-U001)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/audits` | AuditRequest | 201 | Create audit |
| GET | `/audits` |—| 200 | List audits for a project |
| GET | `/audits/{id}` |—| 200 | Get audit by UUID |
| GET | `/audits/uid/{uid}` |—| 200 | Get audit by UID |
| PUT | `/audits/{id}` | UpdateAuditRequest | 200 | Update mutable fields |
| DELETE | `/audits/{id}` |—| 204 | Delete audit (cascades to links) |
| PUT | `/audits/{id}/status` | `{"status": "IN_PROGRESS"}` | 200 | Transition lifecycle status |
| POST | `/audits/{id}/links` | AuditLinkRequest | 201 | Create audit link |
| GET | `/audits/{id}/links` |—| 200 | List links for an audit |
| DELETE | `/audits/{id}/links/{linkId}` |—| 204 | Delete audit link |

All endpoints accept an optional `project` query parameter (same semantics as other
aggregate endpoints).

Audits are a separate aggregate from findings, evidence, and the risk-management
cluster per ADR-047. They capture governed review activities (internal, external,
regulatory, or special) and own the audit lifecycle. Linked compliance frameworks,
assets, controls, risk records, evidence, and findings are represented as outbound
`AuditLink` edges.

**AuditRequest fields:** `uid` (required, max 30), `title` (required, max 200),
`auditType` (required, enum: INTERNAL, EXTERNAL, REGULATORY, SPECIAL),
`scopeDescription` (required), `objectives` (optional, list of strings),
`phases` (optional, list of `AuditPhase` objects with `kind`, `plannedStart`,
`plannedEnd`, `actualStart`, `actualEnd`), `teamMembers` (optional, list of strings).
`createdBy` is set server-side from the authenticated actor and is not accepted
in the request body.

**UpdateAuditRequest fields:** `title`, `auditType`, `scopeDescription`, `objectives`,
`phases`, `teamMembers`, `clearObjectives` (boolean), `clearPhases`
(boolean), `clearTeamMembers` (boolean). Only fields present in the request body are
updated. Clear flags explicitly null the corresponding optional list. `createdBy` is
fixed at creation time and is not mutable.

**AuditLinkRequest fields:** `targetType` (required, AuditLinkTargetType enum),
`targetEntityId` (UUID, for internal first-class targets), `targetIdentifier` (string
max 500, for external / framework targets), `linkType` (required, AuditLinkType enum),
`targetUrl` (optional, max 2000), `targetTitle` (optional, max 255).

**Internal target types (require `targetEntityId`, resolved project-scoped):**
ASSET, CONTROL, RISK_SCENARIO, RISK_REGISTER_RECORD, EVIDENCE, FINDING.

**External target types (require `targetIdentifier`):** FRAMEWORK (compliance
framework reference), EXTERNAL (catch-all for externally managed items).

**Link types:** SCOPES (audit scopes the entity), ASSESSES (audit assesses the
entity), EVIDENCED_BY (audit is evidenced by the linked artifact), FOLLOWS_UP_ON
(audit follows up on the linked finding or record), ASSOCIATED (generic association).

**Lifecycle states:** PLANNED → IN_PROGRESS → DRAFT_REPORT → FINAL_REPORT → CLOSED.
`FINAL_REPORT` can transition back to `DRAFT_REPORT` for rework. `CLOSED` is
terminal.

### Control & Assurance Workspace (GC-Q011)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/controls/workspace` |—| 200 | Read-only composition: controls with scoped implementations, control-test summary and history, latest effectiveness assessment, risk-control mapping count, exceptions (linked findings), evidence-freshness staleness indicator, and per-owner work queues. Filters: `status`, `controlFunction`, `owner`, `assetId`, `asOf`, `freshnessWindowDays` |

### Control Tests (GC-I012)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/control-tests` | ControlTestRequest | 201 | Create a control test evidence row |
| GET | `/control-tests` |—| 200 | List control tests for a project (optional `controlId` filter) |
| GET | `/control-tests/{id}` |—| 200 | Get control test by UUID |
| PUT | `/control-tests/{id}` | UpdateControlTestRequest | 200 | Update mutable fields |
| DELETE | `/control-tests/{id}` |—| 204 | Delete the control test row |

All endpoints accept the same optional `project` query parameter as the rest of `/api/v1/**`.
The control test is the durable, audited evidence record for one execution of a test plan
against a {@link Control}; it is not the same thing as a `ControlEffectivenessAssessment`
(which is a rating, not an execution). See ADR-039.

**ControlTestRequest fields:** `controlId` (required UUID, must belong to the same project),
`uid` (required, max 50), `methodology` (required, ControlTestMethodology enum: INQUIRY,
OBSERVATION, INSPECTION, RE_PERFORMANCE, per PCAOB AS 2201 vocabulary), `testSteps` (required
TEXT), `expectedResults` (required TEXT), `actualResults` (required TEXT), `conclusion`
(required, ControlTestConclusion enum: EFFECTIVE, INEFFECTIVE, NOT_TESTED), `testerIdentity`
(required, max 200—domain provenance; does **not** replace the authenticated audit actor),
`testDate` (required LocalDate, `@PastOrPresent`), `notes` (optional TEXT).

**UpdateControlTestRequest fields:** `methodology`, `testSteps`, `expectedResults`,
`actualResults`, `conclusion`, `testerIdentity`, `testDate`, `notes`—all optional; only
fields present in the request body are updated. `controlId` and `uid` are create-only
(updates ignore them).

### Control Effectiveness Assessments (GC-I013)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/control-effectiveness-assessments` | ControlEffectivenessAssessmentRequest | 201 | Create an effectiveness rating row |
| GET | `/control-effectiveness-assessments` |—| 200 | List assessments for a project (optional `controlId` filter) |
| GET | `/control-effectiveness-assessments/{id}` |—| 200 | Get assessment by UUID |
| PUT | `/control-effectiveness-assessments/{id}` | UpdateControlEffectivenessAssessmentRequest | 200 | Update mutable fields |
| DELETE | `/control-effectiveness-assessments/{id}` |—| 204 | Delete the assessment row |

The assessment is the durable rating record. Design and operating effectiveness are stored
as separate fields because a control can be well-designed but poorly operated, or vice versa
(SOC 2 Type II / SOX testing convention). `operatingEffectiveness` is the stable, audited
read target that future GC-T003 risk-scoring code consumes; this PR does not perform the
residual-risk computation itself. See ADR-039.

**ControlEffectivenessAssessmentRequest fields:** `controlId` (required UUID, same project),
`uid` (required, max 50), `designEffectiveness` (required, ControlEffectivenessRating enum:
EFFECTIVE, PARTIALLY_EFFECTIVE, INEFFECTIVE), `operatingEffectiveness` (required, same enum),
`assessedAt` (required LocalDate, `@PastOrPresent`), `assessor` (required, max 200—domain
provenance), `rationale` (optional TEXT), `notes` (optional TEXT), `supportingTestIds` (optional
list of `ControlTest` UUIDs that support this assessment's operating-effectiveness judgment;
every ID must resolve to a `ControlTest` belonging to the same control as the assessment;
duplicates are de-duplicated; null elements rejected with 422).

**UpdateControlEffectivenessAssessmentRequest fields:** `designEffectiveness`,
`operatingEffectiveness`, `assessedAt`, `assessor`, `rationale`, `notes`, `supportingTestIds`
—all optional; `controlId` and `uid` are create-only. A non-null `supportingTestIds` replaces
the existing list wholesale; pass `null` to leave it unchanged or an empty list to clear it.

**Response includes `supportingTestIds`** as a `List<UUID>`. The graph projection emits one
`SUPPORTED_BY` edge from the assessment to each `ControlTest` listed (plus the standard
`OF_CONTROL` edge to the parent control); edges pointing at non-resolving tests are skipped to
keep AGE materialization safe. `ControlTest` deletion is rejected with HTTP 409
`control_test_referenced` while any assessment still references the test.

### Risk-Control Mapping (GC-T003 / ADR-052)

Bidirectional many-to-many link between controls (catalog `Control` or `ScopedControlImplementation`) and risk items (`RiskScenario` or `RiskRegisterRecord`). The mapping is the canonical aggregation point for control role, objective, scope, methodology influence (C4), anchored observations, and evidence provenance (C8).

#### Scoped Control Implementations

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/scoped-control-implementations` | ScopedControlImplementationRequest | 201 | Create a project-scoped implementation record for a catalog control |
| GET | `/scoped-control-implementations` |—| 200 | List SCIs for the project |
| GET | `/scoped-control-implementations/{id}` |—| 200 | Get SCI by UUID |
| PUT | `/scoped-control-implementations/{id}` | UpdateScopedControlImplementationRequest | 200 | Update mutable fields |
| DELETE | `/scoped-control-implementations/{id}` |—| 204 | Delete SCI |

**ScopedControlImplementationRequest fields:** `uid` (required, max 50), `controlId` (required UUID), `name` (required, max 200), `implementationScope` (optional TEXT), `operationalAssetId` (optional UUID—boundary context).

#### Risk-Control Mappings

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/risk-control-mappings` | RiskControlMappingRequest | 201 | Create a mapping between a control/SCI and a risk scenario/register record |
| GET | `/risk-control-mappings` |—| 200 | List mappings for the project |
| GET | `/risk-control-mappings/{id}` |—| 200 | Get mapping by UUID |
| PUT | `/risk-control-mappings/{id}` | UpdateRiskControlMappingRequest | 200 | Update mutable fields (role, objective, scope, methodology influence) |
| DELETE | `/risk-control-mappings/{id}` |—| 204 | Delete mapping |
| POST | `/risk-control-mappings/{id}/observations` | `{"observationId": "<uuid>"}` | 200 | Attach an observation (C8 provenance) |
| DELETE | `/risk-control-mappings/{id}/observations/{observationId}` |—| 200 | Detach an observation |
| POST | `/risk-control-mappings/{id}/evidence` | AddEvidenceRefRequest | 200 | Add an evidence reference (C8 provenance) |

**RiskControlMappingRequest fields:** Exactly one of `controlId` / `scopedImplementationId` (control side); exactly one of `riskScenarioId` / `riskRegisterRecordId` (risk side); `controlRole` (required, `MappingControlRole`: `PREVENTIVE`, `DETECTIVE`, `CORRECTIVE`, `DETERRENT`, `COMPENSATING`, `RECOVERY`, `DIRECTIVE`); `mappingObjective` (optional TEXT); `mappingScope` (optional TEXT); `operationalAssetId` (optional UUID: C2 boundary context); `methodologyProfileId` (optional UUID: C4 profile); `methodologyInfluence` (optional JSON object—C4 validated against profile schema if profile provided). Violations of the "exactly one" rules return 422.

**AddEvidenceRefRequest fields:** `evidenceRef` (required, opaque reference string), `evidenceNote` (optional TEXT), `evidenceArtifactId` (optional UUID to a formal `EvidenceArtifact`).

#### Compliance Framework Mappings (GC-I002 / GC-I005 / GC-I007 / GC-L011)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/compliance-framework-mappings` | ComplianceFrameworkMappingRequest | 201 | Create a mapping from a requirement (GC-I002) or control (GC-I005) to a compliance-framework element |
| GET | `/compliance-framework-mappings` |—| 200 | List mappings; optionally filter by `framework`, `requirementId`, or `controlId` |
| GET | `/compliance-framework-mappings/{id}` |—| 200 | Get mapping by UUID |
| PUT | `/compliance-framework-mappings/{id}` | UpdateComplianceFrameworkMappingRequest | 200 | Update mutable fields (framework, element, coverage level, rationale) |
| DELETE | `/compliance-framework-mappings/{id}` |—| 204 | Delete mapping |

**ComplianceFrameworkMappingRequest fields:** Exactly one of `requirementId` / `controlId` (source endpoint XOR—violations return 422); `framework` (required, `ComplianceFrameworkIdentifier`: `SOC2`, `SOX`, `ISO_27001`, `NIST_CSF`, `PCI_DSS`); `frameworkIdentifier` (optional free-form string for genuine externals not in the seeded enum); `frameworkVersion` (optional, for example `"2017 TSC"`); `frameworkElement` (required, the specific element label such as `"CC1.1"`); `coverageLevel` (required, `CoverageLevel`: `FULL`, `PARTIAL`, `COMPENSATING`); `rationale` (optional TEXT).

Duplicate `(endpoint, framework, element)` tuples are rejected with `409`. External `frameworkIdentifier` values are sanitized for control characters / newlines per the log-injection guard.

#### Risk-Control Analysis (Coverage Queries)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/analysis/risk-control/unmapped-scenarios` |—| 200 | C5a—Scenarios with no mapped controls |
| GET | `/analysis/risk-control/unmapped-records` |—| 200 | C5b—Register records with no mapped controls (add `?transitive=true` for transitive form) |
| GET | `/analysis/risk-control/unmapped-controls` |—| 200 | C6—Controls not mapped to any relevant scenario (transitive-through-record) |
| GET | `/analysis/risk-control/assessment-feed/{assessmentResultId}` |—| 200 | C7/C8—Feed of effectiveness inputs and observation/evidence provenance for a risk assessment result |

The `unmapped-records` endpoint accepts `transitive` (boolean, default `true`). In transitive mode, a record is considered covered if all its linked scenarios have at least one mapped control; records with zero scenarios always appear in the result. The `assessment-feed` endpoint requires `?project=<slug>` and the assessment-result UUID in the path.

### Evidence Artifacts (GC-M016 / ADR-045)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/evidence-artifacts` | EvidenceArtifactRequest | 201 | Create a new summarized-evidence artifact |
| GET | `/evidence-artifacts` |—| 200 | List artifacts (optional `evidenceType`, `includeSuperseded` filters) |
| GET | `/evidence-artifacts/{id}` |—| 200 | Get an artifact by UUID |
| POST | `/evidence-artifacts/{id}/supersede` | EvidenceArtifactRequest | 201 | Create a new artifact and link the prior one as superseded |
| GET | `/evidence-artifacts/explorer` |—| 200 | Read-only Evidence and State Explorer (GC-Q012): evidence artifacts and observations annotated with freshness state, provenance, affected assets, and downstream finding impact, plus a freshness counts roll-up. Filters: `assetId`, `evidenceType`, `asOf`, `freshnessWindowDays`, `includeSuperseded` |

The aggregate is append-only: there is no PUT and no DELETE. The only post-create
mutation is `/supersede`, which writes the prior artifact's
`supersededByArtifactId` exactly once. Subsequent supersede attempts on an
already-superseded prior return HTTP 409 `evidence_artifact_already_superseded`.

**EvidenceArtifactRequest fields:** `uid` (required, max 50), `title` (required,
max 200), `summary` (required TEXT, max 8000), `evidenceType` (required, one of
`OBSERVATION_SUMMARY`, `CONTROL_TEST_SUMMARY`, `ASSURANCE_CONCLUSION`,
`VERIFICATION_SUMMARY`, `ATTESTATION`, `MIXED`), `derivationMethod` (required, max
200—method/profile identifier), `derivedAt` (required Instant), `assuranceLevel`
(optional, one of `L0`-`L3`), `confidence` (optional, max 50), `notes` (optional
TEXT, max 4000), `sources` (required non-empty list, max 100). Each source
carries `sourceKind` (one of `OBSERVATION`, `CONTROL_TEST`,
`CONTROL_EFFECTIVENESS_ASSESSMENT`, `VERIFICATION_RESULT`,
`RISK_ASSESSMENT_RESULT`, `FINDING`, `ATTESTATION`, `EXTERNAL`), exactly one of
`sourceEntityId` (UUID, for internal kinds) or `sourceIdentifier` (string, for
external kinds `ATTESTATION` / `EXTERNAL`), and an optional `role` (free text).

The service validates internal sources project-scoped via the corresponding
repository (`evidence_source_target_not_found` 422 when the UUID does not
resolve); external sources require only a non-blank `sourceIdentifier`.
`derivedBy` is taken from the authenticated actor at create time and is not
caller-supplied.

The list endpoint excludes superseded artifacts by default; pass
`includeSuperseded=true` to include them. The graph projection emits one
`HAS_SOURCE` edge per internal-kind source pointing at the existing graph node
for the source entity, and a `SUPERSEDED_BY` edge from a prior artifact to its
replacement once supersede has run.

### Test Cases (TC-001 / ADR-040)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-cases` | TestCaseRequest | 201 | Create a project-scoped test-case definition |
| GET | `/test-cases` |—| 200 | List test cases in a project (ordered by `createdAt DESC`) |
| GET | `/test-cases/{id}` |—| 200 | Get a test case by UUID |
| GET | `/test-cases/uid/{uid}` |—| 200 | Get a test case by project-scoped UID |
| PUT | `/test-cases/{id}` | UpdateTestCaseRequest | 200 | Update mutable fields (null = no change) |
| PUT | `/test-cases/{id}/status` | TestCaseStatusTransitionRequest | 200 | Transition the lifecycle status |
| DELETE | `/test-cases/{id}` |—| 204 | Delete the test case |

The `TestCase` aggregate is a reusable, version-controlled, project-scoped definition of an
intended test. It is **definition-only**—it does not record executions, results, suites, or
defects. Those are future aggregates that reference test cases through the existing
project-scoped link patterns. See ADR-040.

**TestCaseRequest fields:** `uid` (required, max 50, unique per project), `title` (required,
max 200), `type` (required, `TestCaseType` enum: `MANUAL`, `AUTOMATED`, `HYBRID`),
`priority` (required, `TestCasePriority` enum: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`),
`format` (optional, `TestCaseFormat` enum: `STEP_BASED`, `GHERKIN`; defaults to `STEP_BASED`
and is immutable after create—see TC-004 / ADR-042),
`description` (optional TEXT, Markdown by convention), `preconditions` (optional TEXT),
`postconditions` (optional TEXT), `estimatedDurationSeconds` (optional non-negative `Long`).

**UpdateTestCaseRequest fields:** `title`, `type`, `priority`, `description`, `preconditions`,
`postconditions`, `estimatedDurationSeconds`—all optional with null-means-no-change. `uid`
is create-only.

**TestCaseStatusTransitionRequest fields:** `status` (required, `TestCaseStatus` enum).
Valid lifecycle transitions are `DRAFT → APPROVED | ARCHIVED`,
`APPROVED → DEPRECATED | ARCHIVED`, `DEPRECATED → APPROVED | ARCHIVED`, with `ARCHIVED`
terminal. Invalid transitions surface as HTTP 422 `invalid_status_transition`. Duplicate UID
within a project returns HTTP 409. Negative `estimatedDurationSeconds` is rejected at the DTO
layer with HTTP 422.

Rich-text fields (`description`, `preconditions`, `postconditions`) are stored as plain text
and rendered as Markdown by clients; no HTML sanitizer is wired through this surface.

### Test Case Steps (TC-002 / ADR-041)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-cases/{testCaseId}/steps` | TestCaseStepRequest | 201 | Create a step in a test case |
| GET | `/test-cases/{testCaseId}/steps` |—| 200 | List steps ordered by `stepNumber` ascending |
| GET | `/test-cases/{testCaseId}/steps/{stepId}` |—| 200 | Get one step |
| PUT | `/test-cases/{testCaseId}/steps/{stepId}` | UpdateTestCaseStepRequest | 200 | Update step fields (null = no change) |
| DELETE | `/test-cases/{testCaseId}/steps/{stepId}` |—| 204 | Delete a step |

Steps are an ordered child collection of a test case. Each step carries a `stepNumber` (unique
within its test case, positive), an `action` (what to do), an `expectedResult` (what should
happen), and an optional `actualResult` (what actually happened on the latest authored pass).
Rich-text fields use the same CommonMark Markdown convention as the parent test case;
inline images use the `![alt](url)` syntax with no backend-side fetching, sanitisation, or
binary storage (see ADR-041 §Rich text and inline images).

**TestCaseStepRequest fields:** `stepNumber` (required positive `Integer`), `action` (required,
max 10000), `expectedResult` (required, max 10000), `actualResult` (optional, max 10000).

**UpdateTestCaseStepRequest fields:** `stepNumber`, `action`, `expectedResult`, `actualResult`:
all optional with null-means-no-change, plus `clearActualResult: true` to wipe the
`actualResult` to null (same partial-update convention as `UpdateTestCaseRequest`).

Duplicate `stepNumber` within a test case returns HTTP 409. Non-positive `stepNumber` and
oversize rich-text fields return HTTP 422. A step request against a test case that is not in
the resolved project returns HTTP 404. Deleting the parent test case cascade-deletes its
steps service-side so Envers captures each step's delete revision.

A step request against a parent test case whose `format` is not `STEP_BASED` (for example, a
Gherkin test case) returns HTTP 409 with a message identifying the actual format—steps
and Gherkin source are mutually exclusive authored formats (TC-004 / ADR-042).

### Test Case BDD/Gherkin Format (TC-004 / ADR-042)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-cases/{testCaseId}/gherkin` | TestCaseGherkinRequest | 201 | Attach Gherkin source to a `GHERKIN`-format test case |
| GET | `/test-cases/{testCaseId}/gherkin` |—| 200 | Retrieve the Gherkin source |
| PUT | `/test-cases/{testCaseId}/gherkin` | UpdateTestCaseGherkinRequest | 200 | Replace the Gherkin source |
| DELETE | `/test-cases/{testCaseId}/gherkin` |—| 204 | Remove the Gherkin source |

Gherkin support is a singleton sub-resource: each test case carries at most one Gherkin
document (UNIQUE on `test_case_id` at the schema layer; HTTP 409 from POST when one already
exists). The canonical authored `.feature` source is stored verbatim as TEXT; the backend
parses it for validation only and never executes glue, expands `Examples` rows into runtime
tests, evaluates expressions, fetches remote includes, or runs Cucumber hooks.

**TestCaseGherkinRequest fields:** `source` (required, max 102400 chars, must parse as
Gherkin with at least one `Feature` and at least one `Scenario`/`Scenario Outline`).

**UpdateTestCaseGherkinRequest fields:** `source` (required, same constraints—full
replacement, no null-means-no-change semantic because the resource is a single field).

**Format gating.** Both POST and PUT require the parent test case's `format` to be
`GHERKIN`; otherwise the request is rejected with HTTP 422 `invalid_test_case_format`.
A `GHERKIN`-format test case may not have step rows; conversely, a `STEP_BASED` test case
may not have a Gherkin document (TC-004 / ADR-042 §Format axis).

**Validation limits.** The parsed Gherkin is bounded server-side:
- max 50 scenarios per feature,
- max 200 data rows per `Examples` table,
- max 4000 characters per `Examples` cell.

Parser failures, oversize source, missing scenarios, or `Scenario Outline` without
`Examples` return HTTP 422 with code `invalid_gherkin_source`. Error details carry
line / column / keyword / field metadata only—never the source text, parser stack
traces, file paths, or `Examples` cell content.

Deleting the parent test case cascade-deletes the Gherkin document service-side so
Envers captures the delete revision (mirrors the step-cascade pattern in ADR-041).

### Test Case Hierarchical Organization (TC-005 / ADR-043)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-cases/folders` | TestCaseFolderRequest | 201 | Create a folder under a project (root) or under another folder |
| GET | `/test-cases/folders` |—| 200 | List folders in a project (ordered by `sortOrder`) |
| GET | `/test-cases/folders/{id}` |—| 200 | Get a folder by id |
| PUT | `/test-cases/folders/{id}` | UpdateTestCaseFolderRequest | 200 | Rename / re-describe a folder |
| DELETE | `/test-cases/folders/{id}` |—| 204 | Delete an **empty** folder (subfolders / test cases must be moved or deleted first) |
| PUT | `/test-cases/folders/{id}/move` | MoveTestCaseFolderRequest | 200 | Move a folder to a new container (cycle / cross-project rejected) |
| PUT | `/test-cases/folders/reorder` | ReorderTestCaseFoldersRequest | 204 | Bulk reorder folders within one container |
| PUT | `/test-cases/{id}/move` | MoveTestCaseRequest | 200 | Move a test case into a folder (or to the project root) |
| POST | `/test-cases/{id}/copy` | CopyTestCaseRequest | 201 | Copy a test case, cloning steps / Gherkin source |
| PUT | `/test-cases/reorder` | ReorderTestCasesRequest | 204 | Bulk reorder test cases within one container |
| GET | `/test-cases/tree` |—| 200 | Nested tree of folders and test cases for the project |

A `TestCaseFolder` is the test-repository organisation aggregate. It is project-scoped, self-referencing
(nullable `parent`), `@Audited`, container-locally ordered by `sortOrder`, and uniquely titled per
container. `TestCase.parentFolderId` (nullable; null ⇒ project root) and `TestCase.sortOrder` carry the
placement. See ADR-043.

**TestCaseFolderRequest fields:** `title` (required, max 200), `description` (optional TEXT),
`parentFolderId` (optional UUID; omit / null = root), `sortOrder` (optional non-negative `Integer`;
omit / null = append at end of container).

**UpdateTestCaseFolderRequest fields:** `title`, `description` (all optional, null-means-no-change),
plus `clearDescription: true` to wipe `description` to null (same partial-update convention as
`UpdateTestCaseRequest`).

**MoveTestCaseFolderRequest / MoveTestCaseRequest fields:** `parentFolderId` (required; null = root),
`sortOrder` (optional non-negative; omit = append).

**ReorderTestCaseFoldersRequest / ReorderTestCasesRequest fields:** `parentFolderId` (required; null
= root), `orderedFolderIds` / `orderedTestCaseIds` (required, must contain exactly the current
siblings—partial reorders are rejected with HTTP 409).

**CopyTestCaseRequest fields:** `newUid` (required, max 50, must not collide with an existing UID in
the same project), `parentFolderId` (explicit target—same convention as `MoveTestCaseRequest`:
`null` or omitted = project root, UUID = that folder), `sortOrder` (optional; defaults to max+1 in
the target container). Callers that want to clone in place must pass the source's `parentFolderId`
explicitly. The copy clones every immutable definition field (title, description, preconditions,
postconditions, priority, type, format, estimatedDurationSeconds), resets `status` to `DRAFT`,
and clones authored children via their owning services (`TestCaseStepService.copyStepsToTestCase`,
`TestCaseGherkinService.copyGherkinToTestCase`). Step `actualResult` is **not** copied; it is
run-time evidence, not part of the definition.

**TestCaseFolderResponse fields:** `id`, `projectIdentifier`, `parentFolderId`, `title`,
`description`, `sortOrder`, `createdAt`, `updatedAt`.

**Tree response.** `GET /test-cases/tree` returns an array of `TestCaseTreeNode`. Each node carries
`kind` (`FOLDER` or `TEST_CASE`), `id`, `parentFolderId`, `title`, `description`, `sortOrder`,
`testCase` (populated only for `TEST_CASE` kind: `{uid, status, type, priority, format}`), and
`children` (folders first by `sortOrder`, then test cases by `sortOrder`; leaves carry an empty
array).

**Error envelope.** Duplicate sibling title returns HTTP 409 `conflict`. Moving a folder under
itself or any descendant returns HTTP 409. Cross-project moves / copies return HTTP 404 (the target
is "not found" in the requesting project's scope). Deleting a non-empty folder returns HTTP 409
with a message naming the contents class. Reordering with a non-matching id set returns HTTP 409.
Copying with a colliding `newUid` returns HTTP 409.

### Test Plans (TC-006 / ADR-044)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-plans` | TestPlanRequest | 201 | Create a project-scoped test plan |
| GET | `/test-plans` |—| 200 | List test plans in a project (ordered by `createdAt DESC`) |
| GET | `/test-plans/{id}` |—| 200 | Get a test plan by UUID |
| GET | `/test-plans/uid/{uid}` |—| 200 | Get a test plan by project-scoped UID |
| PUT | `/test-plans/{id}` | UpdateTestPlanRequest | 200 | Update mutable fields (null = no change; `clearXxx: true` = clear) |
| PUT | `/test-plans/{id}/status` | TestPlanStatusTransitionRequest | 200 | Transition the lifecycle status |
| DELETE | `/test-plans/{id}` |—| 204 | Delete the test plan |

A `TestPlan` is the top-level planning container for a testing effort. It is project-scoped,
flat (plans do not nest), and carries scope metadata (name, description), release coordinates
(product, version, build) as bounded scalar text, a lifecycle status, and planned start / end
dates. The aggregate's stable UUID primary key is the seam future `TestRun` rows will FK to
in order to group multiple runs under a single plan; no JSON array of run IDs lives on the
plan itself. See ADR-044.

**TestPlanRequest fields:** `uid` (required, max 50, unique per project), `name` (required,
max 200), `description` (optional, max 8192), `product` (optional, max 200), `version`
(optional, max 100), `build` (optional, max 100), `startDate` (optional, ISO-8601 date),
`endDate` (optional, ISO-8601 date; must be `>= startDate` when both are set).

**UpdateTestPlanRequest fields:** `name`, `description`, `product`, `version`, `build`,
`startDate`, `endDate`: all optional with null-means-no-change, plus
`clearDescription`, `clearProduct`, `clearVersion`, `clearBuild`, `clearStartDate`,
`clearEndDate` flags to wipe the matching field to null (same partial-update convention as
`UpdateTestCaseRequest`). `uid` is create-only.

**TestPlanStatusTransitionRequest fields:** `status` (required, `TestPlanStatus` enum:
`DRAFT`, `ACTIVE`, `IN_PROGRESS`, `COMPLETED`, `ARCHIVED`). Valid transitions:
`DRAFT → ACTIVE | ARCHIVED`, `ACTIVE → IN_PROGRESS | COMPLETED | ARCHIVED`,
`IN_PROGRESS → ACTIVE | COMPLETED | ARCHIVED`, `COMPLETED → ACTIVE | ARCHIVED`,
with `ARCHIVED` terminal. The `IN_PROGRESS → ACTIVE` and `COMPLETED → ACTIVE` arcs exist
so a team can pause a run window or re-open a completed plan to fold in late-arriving runs.
Invalid transitions surface as HTTP 422 `invalid_status_transition`. Duplicate UID within a
project returns HTTP 409. An inverted `startDate` / `endDate` pair surfaces as HTTP 422
`invalid_test_plan_schedule`.

### Test Suites (TC-007 / ADR-047)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-suites` | TestSuiteRequest | 201 | Create a project-scoped test suite with an immutable `populationMode` |
| GET | `/test-suites` |—| 200 | List test suites in a project (ordered by `createdAt DESC`) |
| GET | `/test-suites/{id}` |—| 200 | Get a test suite by UUID |
| GET | `/test-suites/uid/{uid}` |—| 200 | Get a test suite by project-scoped UID |
| PUT | `/test-suites/{id}` | UpdateTestSuiteRequest | 200 | Update mutable fields (null = no change; `clearXxx: true` = clear)—`populationMode` is immutable |
| DELETE | `/test-suites/{id}` |—| 204 | Delete the test suite (cascades members / source requirements) |
| GET | `/test-suites/{id}/test-cases` |—| 200 | RESOLVE—return the suite's test cases dispatched on `populationMode` |
| POST | `/test-suites/{id}/members` | AddTestSuiteMemberRequest | 201 | STATIC only—add a test case to the suite |
| GET | `/test-suites/{id}/members` |—| 200 | STATIC only—list members in position order |
| DELETE | `/test-suites/{id}/members/{testCaseId}` |—| 204 | STATIC only—remove a member |
| PUT | `/test-suites/{id}/members/reorder` | ReorderTestSuiteMembersRequest | 200 | STATIC only—reorder members |
| POST | `/test-suites/{id}/source-requirements` | AddTestSuiteSourceRequirementRequest | 201 | REQUIREMENTS_BASED only—add a source requirement |
| GET | `/test-suites/{id}/source-requirements` |—| 200 | REQUIREMENTS_BASED only—list sources |
| DELETE | `/test-suites/{id}/source-requirements/{requirementId}` |—| 204 | REQUIREMENTS_BASED only—remove a source |

A `TestSuite` is the selection container for test cases inside a project. It carries a single
**immutable** `populationMode` chosen at create time:

- `STATIC`—manually selected test cases held as explicit `test_suite_member` rows. Add /
  remove / reorder via the `/members` endpoints. Resolve returns members in `position` order.
- `REQUIREMENTS_BASED`—auto-populated from one or more source requirements. Add / remove via
  the `/source-requirements` endpoints. Resolve returns the test cases linked to those
  requirements through `TraceabilityLink` rows whose `linkType = TESTS` and
  `artifactType = TEST` (the `artifactIdentifier` is the test case's project-scoped UID).
- `QUERY_BASED`—auto-populated from typed filter criteria stored as columns on the suite
  (`criteriaStatus`, `criteriaType`, `criteriaPriority`, `criteriaFormat`, `criteriaFolderId`,
  `criteriaTextSearch`). Resolve runs the criteria against the test-case repository at
  read time; results are **dynamic**—they change as matching cases change. At least one
  criterion must be set on create and on every update.

**Mode immutability.** Switching modes would orphan member / source / criteria state and
break the resolve-time dispatch contract. The entity has no setter for `populationMode`,
the controller rejects `populationMode` on updates, and a `CHECK` constraint at the SQL
layer backstops the invariant. Mode-mismatch operations (adding members to a non-STATIC
suite, etc.) return HTTP 422 `invalid_test_suite_mode_operation`.

**Result cap.** Resolve returns at most 500 test cases per call across all three modes;
this is a service-level constant today (no `?page=` parameter). A future requirement can
promote it to a pageable parameter.

**TestSuiteRequest fields:** `uid` (required, max 50, unique per project), `name` (required,
max 200), `description` (optional, max 8192), `populationMode` (required, one of `STATIC`,
`REQUIREMENTS_BASED`, `QUERY_BASED`), plus per-mode criteria fields valid only for
`QUERY_BASED` (`criteriaStatus`, `criteriaType`, `criteriaPriority`, `criteriaFormat`,
`criteriaFolderId`, `criteriaTextSearch`—max 200).

**UpdateTestSuiteRequest fields:** `name`, `description`, all `criteriaXxx` fields: all
optional with null-means-no-change, plus `clearDescription`, `clearCriteriaStatus`,
`clearCriteriaType`, `clearCriteriaPriority`, `clearCriteriaFormat`,
`clearCriteriaFolderId`, `clearCriteriaTextSearch` flags to wipe the matching field to
null. `uid` and `populationMode` are create-only.

**AddTestSuiteMemberRequest fields:** `testCaseId` (required, UUID), `position` (optional,
non-negative; defaults to `max(position) + 1` for append-on-end semantics).

**ReorderTestSuiteMembersRequest fields:** `orderedTestCaseIds` (required, non-empty). The
list must contain exactly the current member test-case ids—no extras, no omissions, no
duplicates. The reorder uses the same shared `SiblingOrderingHelper` as the test-case and
test-case-folder reorder endpoints, so the error envelope matches: a set-mismatch returns
HTTP 409 (the partial/mismatched-siblings case); a null/duplicate id in the input list
returns HTTP 422 with `invalid_reorder`.

**AddTestSuiteSourceRequirementRequest fields:** `requirementId` (required, UUID; must be
in the same project).

**Error envelope.** Duplicate UID within a project returns HTTP 409. Member / source rows
that already exist return HTTP 409. Cross-project test cases, requirements, or folder
references return HTTP 404 (project-scoped lookup). Setting criteria on a non-QUERY_BASED
suite, or clearing the last criterion of a QUERY_BASED suite, returns HTTP 422
(`invalid_test_suite_mode_field` / `invalid_test_suite_query`).

### Test Runs (TC-008 / ADR-049)

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/test-runs` | TestRunRequest | 201 | Create a project-scoped test run; snapshots the suite's resolved cases as `test_run_case_result` rows |
| GET | `/test-runs` |—| 200 | List test runs in a project (ordered by `createdAt DESC`) |
| GET | `/test-runs/{id}` |—| 200 | Get a test run by UUID |
| GET | `/test-runs/uid/{uid}` |—| 200 | Get a test run by project-scoped UID |
| PUT | `/test-runs/{id}` | UpdateTestRunRequest | 200 | Update mutable fields (null = no change; `clearXxx: true` = clear) |
| PUT | `/test-runs/{id}/status` | TestRunStatusTransitionRequest | 200 | Transition the lifecycle status |
| DELETE | `/test-runs/{id}` |—| 204 | Delete the test run (cascades testers and case-result rows) |
| POST | `/test-runs/{id}/testers` | AddTestRunTesterRequest | 201 | Assign a tester to the run |
| GET | `/test-runs/{id}/testers` |—| 200 | List assigned testers |
| DELETE | `/test-runs/{id}/testers/{testerName}` |—| 204 | Remove a tester |
| GET | `/test-runs/{id}/results` |—| 200 | List per-case execution results (ordered by `snapshotOrder`) |
| PUT | `/test-runs/{id}/results/{testCaseId}` | UpdateTestRunCaseResultRequest | 200 | Update the per-case status and optional notes |
| GET | `/test-runs/{id}/results/{caseResultId}/steps` |—| 200 | List per-step execution results for a case (TC-009 / ADR-050; ordered by `snapshotOrder`) |
| PUT | `/test-runs/{id}/results/{caseResultId}/steps/{stepResultId}` | UpdateTestRunStepResultRequest | 200 | Update per-step status, comment, and execution timestamp |
| PUT | `/test-runs/{id}/cursor` | UpdateTestRunCursorRequest | 200 | Set / clear the pause-resume cursor (TC-009 / ADR-050) |

A `TestRun` is the execution-time record for one pass through a `TestSuite` against a
`TestPlan` for a specific environment / version / build window. The aggregate is
project-scoped, references the driving plan and suite via FKs, and owns its execution
evidence directly through two child aggregates: `TestRunTesterAssignment` (assigned
testers) and `TestRunCaseResult` (per-case execution outcomes). See ADR-049.

**Snapshot on create.** When a run is created, the service resolves the suite via
`TestSuiteService.resolveTestCases` (capped at 500 results) and snapshots the resulting
cases as `test_run_case_result` rows. The snapshot is the canonical membership of the
run: subsequent mutations to the source suite (member changes, criteria edits) do **not**
rewrite the run's case set. Each result row carries `testCaseUid`, `testCaseTitle`, and
`snapshotOrder` snapshots captured at create time so later edits to the linked `TestCase`
or its position in the source suite never rewrite historical evidence. `GET /test-runs/{id}/results`
replays rows in `snapshotOrder` (the resolver's order at create time—author position for
STATIC suites, UID order otherwise), not by the case's current UID.

**TestRunRequest fields:** `uid` (required, max 50, unique per project), `name` (required,
max 200), `testPlanId` (required, UUID), `testSuiteId` (required, UUID), `environment`
(optional, max 100), `version` (optional, max 100), `build` (optional, max 100),
`startAt` (optional, ISO-8601 timestamp), `endAt` (optional, ISO-8601 timestamp; must be
`>= startAt` when both are set).

**UpdateTestRunRequest fields:** `name`, `environment`, `version`, `build`, `startAt`,
`endAt`: all optional with null-means-no-change, plus `clearEnvironment`, `clearVersion`,
`clearBuild`, `clearStartAt`, `clearEndAt` flags to wipe the matching field to null.
`uid`, `testPlanId`, and `testSuiteId` are create-only.

**TestRunStatusTransitionRequest fields:** `status` (required, `TestRunStatus` enum:
`PLANNED`, `IN_PROGRESS`, `COMPLETED`, `ABORTED`, `ARCHIVED`). Valid transitions:
`PLANNED → IN_PROGRESS | ABORTED | ARCHIVED`,
`IN_PROGRESS → COMPLETED | ABORTED | ARCHIVED`,
`COMPLETED → ARCHIVED`, `ABORTED → ARCHIVED`, `ARCHIVED → ∅` (terminal). Unlike
`TestPlanStatus`, there are no backwards arcs out of `COMPLETED` or `ABORTED`: a run is
a single execution pass; re-running is a new run. Invalid transitions surface as HTTP 422
`invalid_status_transition`. Duplicate UID within a project returns HTTP 409. Cross-project
plan / suite / test-case references return HTTP 404 (concealment).

**AddTestRunTesterRequest fields:** `testerName` (required, max 120, character set
`[A-Za-z0-9 _.\-'@]+`). Tester names are domain-provenance values, not principals in
the Spring Security `users` table (ADR-037). The character set is constrained at create
because `DELETE /test-runs/{id}/testers/{testerName}` addresses the name as a URL path
segment; URL-reserved characters (slash, question mark, hash, percent, etc.) would be
non-round-trippable and are rejected with HTTP 422. Duplicate `(runId, testerName)`
returns HTTP 409.

**UpdateTestRunCaseResultRequest fields:** `status` (required, `TestRunCaseResultStatus`
enum: `NOT_RUN`, `PASSED`, `FAILED`, `BLOCKED`, `SKIPPED`), `notes` (optional, max 8192),
`clearNotes` (boolean, wipes notes to null). There is no transition graph for
per-case result status—a tester may flip a result freely as re-tests, descopes, and
unblocks happen over the life of a run. Attempting to update a result for a case that
is not part of the run's snapshot returns HTTP 404.

**Manual test execution runner (TC-009 / ADR-050).** When a run is created, the service
also snapshots every authored `TestCaseStep` of every resolved case as a
`test_run_step_result` child of the parent `test_run_case_result` row. Later edits to
the authored step never rewrite a run's historical evidence (the
`action_snapshot` / `expected_result_snapshot` / `step_number_snapshot` columns are
authoritative for replay). Per-step status uses the same `TestRunCaseResultStatus`
vocabulary as the case-level status; no parallel enum is introduced. `GET
/test-runs/{id}/results/{caseResultId}/steps` replays rows in `snapshotOrder`. Pause
and resume are persisted on the parent run via the
`current_case_result_id` / `current_step_result_id` cursor columns; these are
`@NotAudited` so cursor movement does not generate per-step `test_run_audit`
revisions. Per-case status is NOT auto-rolled-up from per-step status—a tester may
mark a case `BLOCKED` even when some steps `PASSED` (and vice versa).

**UpdateTestRunStepResultRequest fields:** `status` (required, `TestRunCaseResultStatus`
enum), `comment` (optional, max 8192 chars; per-step tester note), `clearComment`
(boolean, wipes comment to null), `executedAt` (optional, ISO-8601 timestamp; the
moment the tester observed the step), `clearExecutedAt` (boolean, wipes the timestamp).
Attempting to update a step result whose `caseResultId` is not part of the run, or
whose `stepResultId` is not part of the case-result, returns HTTP 404.

**UpdateTestRunCursorRequest fields:** `currentCaseResultId` (optional UUID; must
identify a case-result row that belongs to this run), `currentStepResultId` (optional
UUID; must identify a step-result row that belongs to the supplied case-result,
and `currentCaseResultId` must therefore also be supplied), `clearCursor` (boolean;
when true, both fields are nulled regardless of the supplied UUIDs). The cursor is
ephemeral runner-UI state and is intentionally NOT audited.

## Request / Response Format

JSON. Error responses use a nested envelope:

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Requirement not found",
    "detail": {}
  }
}
```

HTTP status codes: 201 (created), 200 (ok), 204 (deleted), 404 (not found),
409 (conflict), 422 (validation error).

## Filtering

`GET /api/v1/requirements` accepts query parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | enum | DRAFT, ACTIVE, DEPRECATED, ARCHIVED |
| `type` | enum | FUNCTIONAL, NON_FUNCTIONAL, CONSTRAINT, INTERFACE |
| `wave` | integer | Wave number |
| `search` | string | Free-text search in title and statement |

Archived requirements are excluded by default. Filter by `status=ARCHIVED`
to include them.

## Pagination

Standard Spring Page parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-based) |
| `size` | 20 | Page size |
| `sort` | (none) | Sort field and direction (for example, `sort=uid,asc`) |

Response wraps results in a Spring Page object with `content`, `totalElements`,
`totalPages`, `number`, `size`.

### Pack Registry

All pack registry, trust policy, and pack install record routes require an
ADMIN-role bearer token: `Authorization: Bearer <token>`. Tokens and their
audit principal names are configured under the unified
`groundcontrol.security.credentials` list (`role: ADMIN`); see ADR-026 and
the deployment env-var reference. The repo-local MCP helper forwards
`GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN` when set.

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/pack-registry` | RegisterPackRequest | 201 | Register pack version in catalog |
| POST | `/pack-registry/import` | multipart/form-data | 201 | Import and register a pack from uploaded JSON |
| GET | `/pack-registry` |—| 200 | List registry entries (optional `packType` filter) |
| GET | `/pack-registry/{packId}` |—| 200 | List versions of a pack |
| GET | `/pack-registry/{packId}/{version}` |—| 200 | Get specific pack version |
| PUT | `/pack-registry/{packId}/{version}` | UpdatePackRegistryEntryRequest | 200 | Update pack metadata |
| PUT | `/pack-registry/{packId}/{version}/withdraw` |—| 200 | Withdraw pack version |
| DELETE | `/pack-registry/{packId}/{version}` |—| 204 | Delete pack version |
| POST | `/pack-registry/resolve` | ResolvePackRequest | 200 | Resolve version from registry |
| POST | `/pack-registry/check-compatibility` | ResolvePackRequest | 200 | Check pack compatibility (returns boolean) |

For large catalogs, use `POST /pack-registry/import` instead of hand-authoring a
giant JSON request body. The endpoint accepts a multipart `file` part plus an
optional JSON `options` part. Supported formats are:

- `AUTO`—detect OSCAL catalog JSON vs Ground Control manifest JSON
- `OSCAL_JSON`—treat the file as an OSCAL catalog and flatten controls into a `CONTROL_PACK`
- `GC_MANIFEST`—treat the file as a Ground Control pack manifest and register it directly

`options` may override pack metadata such as `packId`, `version`, `publisher`,
`description`, `sourceUrl`, `checksum`, `signatureInfo`, `compatibility`,
`dependencies`, `provenance`, `registryMetadata`, and
`defaultControlFunction` for imported control entries.

Example multipart call:

```sh
curl -X POST "http://localhost:8000/api/v1/pack-registry/import?project=ground-control" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/catalog.json;type=application/json" \
  -F 'options={"format":"OSCAL_JSON","packId":"nist-sp800-53-rev5","version":"5.1.0","publisher":"NIST"};type=application/json'
```

### Trust Policies

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/trust-policies` | CreateTrustPolicyRequest | 201 | Create trust policy |
| GET | `/trust-policies` |—| 200 | List trust policies |
| GET | `/trust-policies/{id}` |—| 200 | Get trust policy |
| PUT | `/trust-policies/{id}` | UpdateTrustPolicyRequest | 200 | Update trust policy |
| DELETE | `/trust-policies/{id}` |—| 204 | Delete trust policy |

### Pack Install Records

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| POST | `/pack-install-records/install` | InstallPackRequest | 201, 422 | Install pack via registry with trust evaluation |
| POST | `/pack-install-records/upgrade` | InstallPackRequest | 200, 422 | Upgrade pack via registry with trust evaluation |
| GET | `/pack-install-records` |—| 200 | List install records (optional `packId` filter) |
| GET | `/pack-install-records/{id}` |—| 200 | Get install record |

### Admin Users (ADR-037)

Browser-session lifecycle for the JDBC user store. Gated by `ROLE_ADMIN` on the
same path matrix as the rest of `/api/v1/admin/**`. Bearer agents that hold an
`ADMIN`-role token may call these endpoints too; the typical caller is the SPA
admin page operating under the signed-in operator's session.

| Method | Path | Body | Status | Purpose |
|--------|------|------|--------|---------|
| GET | `/admin/users` |—| 200 | List users (`username`, `role`, `enabled`) |
| POST | `/admin/users` | `CreateUserRequest` | 201, 409, 422 | Create user. `409 user_exists` on duplicate username; `422 validation_error` for bad username / short password. |
| PATCH | `/admin/users/{username}/role` | `{"role":"USER"\|"ADMIN"}` | 200, 404, 409, 422 | Change role. `409 last_admin` refuses demoting the last enabled admin. |
| PATCH | `/admin/users/{username}/enabled` | `{"enabled":bool}` | 200, 404, 409, 422 | Enable / disable. `409 last_admin` refuses disabling the last enabled admin. |
| DELETE | `/admin/users/{username}` | (none) | 204, 404, 409 | Delete user. `409 last_admin` refuses deleting the last enabled admin. |

`CreateUserRequest`: `{"username":"<lowercase, 2-64 chars, matches /^[a-z][a-z0-9._-]{1,63}$/>", "password":"<12-200 chars>", "role":"USER"\|"ADMIN"}`. Passwords are BCrypt-hashed server-side; the JSON never echoes the password back. First-admin bootstrap is out of band; see `DEPLOYMENT.md`'s Web UI login section.

For control packs, use `/pack-registry/import` or `/pack-registry` to persist the
pack definition first, then call one of these routes with the `packId` and optional
version constraint.

`RegisterPackRequest` and `UpdatePackRegistryEntryRequest` accept
`controlPackEntries` for `CONTROL_PACK` artifacts. Registry-driven install and
upgrade now materialize that stored server-side content; `InstallPackRequest`
contains only `packId` and optional `versionConstraint`. The install record
`performedBy` value is derived server-side from the authenticated admin token,
not request JSON.

When a `checksum` is supplied, the server verifies it against the canonical
pack payload and normalizes the stored value to `sha256:<hex>`. Unsigned packs
may omit `checksum`; they still produce a computed `verifiedChecksum` during
trust evaluation and install recording, but they do not become
`checksumVerified=true` by registry round-trip alone.

`signatureInfo` is optional detached signature metadata with this shape:
`algorithm` (required, one of `SHA256withRSA`, `SHA384withRSA`,
`SHA512withRSA`, `SHA256withECDSA`, `SHA384withECDSA`, `SHA512withECDSA`,
`Ed25519`, or `Ed448`),
`publicKey` (required, base64 DER or PEM-encoded X.509 public key),
`signature` (required, base64 detached signature over the canonical pack
payload), and `keyAlgorithm` (optional when it can be inferred from
`algorithm`, otherwise required). A valid signature is cryptographic evidence
only. Trust policy must use `signerTrusted`, which becomes `true` only when the
signature public key matches a configured trusted signer under
`ground-control.pack-registry.security.trusted-signers`.

Install and upgrade return `422 Unprocessable Entity` when the request is
accepted syntactically but the resolved pack is rejected or fails to apply.

Trust policy rules may match not only raw pack metadata, but also verified
integrity fields exposed by the server: `verifiedChecksum`,
`checksumVerified`, and `signerTrusted`. The `signatureVerified` field is
informational and is rejected in trust policy rules. Regex policy rules are also
disabled; use bounded operators `EQUALS`, `NOT_EQUALS`, `CONTAINS`, and
`IN_LIST`.

## Interactive Docs

- Swagger UI: `http://localhost:8000/api/docs`
- OpenAPI JSON: `http://localhost:8000/api/openapi.json`
