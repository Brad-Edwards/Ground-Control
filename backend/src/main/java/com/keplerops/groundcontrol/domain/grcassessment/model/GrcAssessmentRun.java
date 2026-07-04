package com.keplerops.groundcontrol.domain.grcassessment.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentRunState;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.MapListConverter;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.StringListConverter;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.StringObjectMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "grc_assessment_run")
public class GrcAssessmentRun extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private GrcAssessmentMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 40)
    private GrcAssessmentScopeType scopeType;

    @Convert(converter = StringListConverter.class)
    @Column(name = "scope_values", columnDefinition = "TEXT")
    private List<String> scopeValues;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "base_commit_sha", length = 64)
    private String baseCommitSha;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> languages;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> surfaces;

    @Convert(converter = MapListConverter.class)
    @Column(name = "declared_boundaries", columnDefinition = "TEXT")
    private List<Map<String, Object>> declaredBoundaries;

    @Column(name = "threat_pack_id", length = 200)
    private String threatPackId;

    @Column(name = "threat_pack_version", length = 100)
    private String threatPackVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_policy", nullable = false, length = 30)
    private GrcAssessmentReviewPolicy reviewPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_decision", nullable = false, length = 30)
    private GrcAssessmentReviewDecision reviewDecision = GrcAssessmentReviewDecision.REQUEST_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private GrcAssessmentRunState state = GrcAssessmentRunState.READY_FOR_REVIEW;

    @Column(name = "reviewed_by", length = 200)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_rationale", length = 2000)
    private String reviewRationale;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "partition_count", nullable = false)
    private int partitionCount;

    @Column(name = "deduped_partition_count", nullable = false)
    private int dedupedPartitionCount;

    @Column(name = "duplicate_partition_count", nullable = false)
    private int duplicatePartitionCount;

    @Convert(converter = MapListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<Map<String, Object>> partitions;

    @Convert(converter = StringObjectMapConverter.class)
    @Column(name = "merge_summary", columnDefinition = "TEXT")
    private Map<String, Object> mergeSummary;

    @Convert(converter = MapListConverter.class)
    @Column(name = "graph_effects", columnDefinition = "TEXT")
    private List<Map<String, Object>> graphEffects;

    @Column(name = "graph_effect_count", nullable = false)
    private int graphEffectCount;

    protected GrcAssessmentRun() {
        // JPA
    }

    public GrcAssessmentRun(
            Project project,
            GrcAssessmentMode mode,
            GrcAssessmentScopeType scopeType,
            String commitSha,
            String baseCommitSha,
            List<String> languages,
            List<String> surfaces,
            GrcAssessmentReviewPolicy reviewPolicy,
            String idempotencyKey) {
        this.project = project;
        this.mode = mode;
        this.scopeType = scopeType;
        this.commitSha = commitSha;
        this.baseCommitSha = baseCommitSha;
        this.languages = copyList(languages);
        this.surfaces = copyList(surfaces);
        this.reviewPolicy = reviewPolicy == null ? GrcAssessmentReviewPolicy.REQUIRED : reviewPolicy;
        this.idempotencyKey = idempotencyKey;
        this.scopeValues = List.of();
        this.declaredBoundaries = List.of();
        this.partitions = List.of();
        this.mergeSummary = Map.of();
        this.graphEffects = List.of();
    }

    public Project getProject() {
        return project;
    }

    public GrcAssessmentMode getMode() {
        return mode;
    }

    public GrcAssessmentScopeType getScopeType() {
        return scopeType;
    }

    public List<String> getScopeValues() {
        return copyList(scopeValues);
    }

    public void setScopeValues(List<String> scopeValues) {
        this.scopeValues = copyList(scopeValues);
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getBaseCommitSha() {
        return baseCommitSha;
    }

    public List<String> getLanguages() {
        return copyList(languages);
    }

    public List<String> getSurfaces() {
        return copyList(surfaces);
    }

    public List<Map<String, Object>> getDeclaredBoundaries() {
        return copyMapList(declaredBoundaries);
    }

    public void setDeclaredBoundaries(List<Map<String, Object>> declaredBoundaries) {
        this.declaredBoundaries = copyMapList(declaredBoundaries);
    }

    public String getThreatPackId() {
        return threatPackId;
    }

    public void setThreatPack(String threatPackId, String threatPackVersion) {
        this.threatPackId = threatPackId;
        this.threatPackVersion = threatPackVersion;
    }

    public String getThreatPackVersion() {
        return threatPackVersion;
    }

    public GrcAssessmentReviewPolicy getReviewPolicy() {
        return reviewPolicy;
    }

    public GrcAssessmentReviewDecision getReviewDecision() {
        return reviewDecision;
    }

    public void setReviewDecision(GrcAssessmentReviewDecision reviewDecision, String reviewedBy, String rationale) {
        this.reviewDecision = reviewDecision == null ? GrcAssessmentReviewDecision.REQUEST_REVIEW : reviewDecision;
        this.reviewedBy = reviewedBy;
        this.reviewRationale = rationale;
        if (reviewDecision == GrcAssessmentReviewDecision.APPROVED
                || reviewDecision == GrcAssessmentReviewDecision.REJECTED) {
            this.reviewedAt = Instant.now();
        }
    }

    public GrcAssessmentRunState getState() {
        return state;
    }

    public void setState(GrcAssessmentRunState state) {
        this.state = state;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewRationale() {
        return reviewRationale;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    public int getDedupedPartitionCount() {
        return dedupedPartitionCount;
    }

    public int getDuplicatePartitionCount() {
        return duplicatePartitionCount;
    }

    public List<Map<String, Object>> getPartitions() {
        return copyMapList(partitions);
    }

    public Map<String, Object> getMergeSummary() {
        return mergeSummary == null ? Map.of() : Map.copyOf(mergeSummary);
    }

    public List<Map<String, Object>> getGraphEffects() {
        return copyMapList(graphEffects);
    }

    public int getGraphEffectCount() {
        return graphEffectCount;
    }

    public void recordPartitions(int requestedPartitionCount, List<Map<String, Object>> partitions, int uniqueCount) {
        this.partitionCount = requestedPartitionCount;
        this.dedupedPartitionCount = uniqueCount;
        this.duplicatePartitionCount = Math.max(0, requestedPartitionCount - uniqueCount);
        this.partitions = copyMapList(partitions);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestedPartitionCount", requestedPartitionCount);
        summary.put("dedupedPartitionCount", uniqueCount);
        summary.put("duplicatePartitionCount", this.duplicatePartitionCount);
        this.mergeSummary = summary;
    }

    public void recordGraphEffects(List<Map<String, Object>> graphEffects) {
        this.graphEffects = copyMapList(graphEffects);
        this.graphEffectCount = this.graphEffects.size();
        this.state = GrcAssessmentRunState.COMMITTED;
        this.reviewDecision = GrcAssessmentReviewDecision.APPROVED;
    }

    private static List<String> copyList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<Map<String, Object>> copyMapList(List<Map<String, Object>> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> value == null ? Map.<String, Object>of() : new LinkedHashMap<>(value))
                .toList();
    }
}
