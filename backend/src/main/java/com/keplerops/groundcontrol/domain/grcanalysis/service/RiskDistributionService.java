package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T008 distribution view over {@link RiskRegisterRecord}. Counts the
 * register rows for the project bucketed by the requested axis. The axis is
 * methodology-agnostic so the envelope's {@code scale}/{@code units} are
 * fixed; methodology family does not gate output.
 *
 * <p>Asset-criticality grouping is supported only when a register row carries
 * a single resolvable {@link com.keplerops.groundcontrol.domain.assets.model.OperationalAsset}
 * link target through one of its scenarios; otherwise the row is counted into
 * an {@code UNCLASSIFIED} bucket and a project-level limitation is emitted.
 */
@Service
@Transactional(readOnly = true)
public class RiskDistributionService {

    static final String ANALYSIS_KIND = "risk_distribution";
    static final String DERIVATION_METHOD = "risk-register-distribution-v1";
    static final String SCALE = "nominal";
    static final String UNITS = "register record counts";
    static final String UNCLASSIFIED_KEY = "UNCLASSIFIED";
    static final String UNCLASSIFIED_LIMITATION_FMT =
            "%d register records had no resolvable %s and are reported in the UNCLASSIFIED bucket";
    static final String ASSET_CRITICALITY_NOT_AVAILABLE =
            "asset-criticality grouping requires per-record asset attribution, which is not maintained"
                    + " directly on RiskRegisterRecord; the projection falls back to UNCLASSIFIED for all rows"
                    + " (GC-T008 / cluster-3 carve-out)";

    private final RiskRegisterRecordRepository registerRepository;
    private final ProjectRepository projectRepository;

    // Reserved for the future asset-criticality grouping path (GC-T008); kept here so
    // the projection stays a single service when the carve-out lifts.
    @SuppressWarnings("unused")
    private final OperationalAssetRepository operationalAssetRepository;

    public RiskDistributionService(
            RiskRegisterRecordRepository registerRepository,
            ProjectRepository projectRepository,
            OperationalAssetRepository operationalAssetRepository) {
        this.registerRepository = registerRepository;
        this.projectRepository = projectRepository;
        this.operationalAssetRepository = operationalAssetRepository;
    }

    public RiskDistributionResult distribute(UUID projectId, Instant asOf, RiskDistributionGroupBy groupBy) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(groupBy, "groupBy");
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();

        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        List<RiskRegisterRecord> records =
                registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);

        Map<String, Integer> counts = new TreeMap<>();
        int unclassified = 0;
        for (RiskRegisterRecord registerRecord : records) {
            List<String> keys = keysFor(groupBy, registerRecord);
            if (keys.isEmpty()) {
                counts.merge(UNCLASSIFIED_KEY, 1, Integer::sum);
                unclassified++;
                continue;
            }
            for (String key : keys) {
                counts.merge(key, 1, Integer::sum);
            }
        }

        List<RiskDistributionResult.DistributionBucket> buckets = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            buckets.add(
                    new RiskDistributionResult.DistributionBucket(entry.getKey(), entry.getKey(), entry.getValue()));
        }

        List<String> limitations = new ArrayList<>();
        if (groupBy == RiskDistributionGroupBy.ASSET_CRITICALITY) {
            limitations.add(ASSET_CRITICALITY_NOT_AVAILABLE);
        } else if (unclassified > 0) {
            limitations.add(String.format(
                    UNCLASSIFIED_LIMITATION_FMT, unclassified, groupBy.name().toLowerCase()));
        }

        return new RiskDistributionResult(
                ANALYSIS_KIND,
                project.getIdentifier(),
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE,
                UNITS,
                new RiskDistributionResult.Inputs(project.getIdentifier(), effectiveAsOf, groupBy.name()),
                buckets,
                new RiskDistributionResult.Counts(
                        records.size(), records.size(), unclassified, new LinkedHashMap<>(counts)),
                limitations);
    }

    private List<String> keysFor(RiskDistributionGroupBy groupBy, RiskRegisterRecord registerRecord) {
        return switch (groupBy) {
            case STATUS -> registerRecord.getStatus() == null
                    ? List.of()
                    : List.of(registerRecord.getStatus().name());
            case OWNER -> {
                String owner = registerRecord.getOwner();
                yield owner == null || owner.isBlank() ? List.of() : List.of(owner.trim());
            }
            case CATEGORY -> {
                List<String> tags = registerRecord.getCategoryTags();
                if (tags == null || tags.isEmpty()) {
                    yield List.of();
                }
                List<String> clean = new ArrayList<>();
                for (String t : tags) {
                    if (t != null && !t.isBlank()) {
                        clean.add(t.trim());
                    }
                }
                yield clean;
            }
            case ASSET_CRITICALITY -> List.of();
        };
    }
}
