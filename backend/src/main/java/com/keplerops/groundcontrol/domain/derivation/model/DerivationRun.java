package com.keplerops.groundcontrol.domain.derivation.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.StringListConverter;
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
import java.util.List;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "derivation_run")
public class DerivationRun extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_mode", nullable = false, length = 20)
    private DerivationScopeMode scopeMode;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "base_commit_sha", length = 64)
    private String baseCommitSha;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> paths;

    @Convert(converter = StringListConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> languages;

    @Convert(converter = StringListConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> surfaces;

    @Column(name = "requested_by", length = 200)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "adapter_count", nullable = false)
    private int adapterCount;

    @Column(name = "fact_count", nullable = false)
    private int factCount;

    @Column(name = "capture_limit_count", nullable = false)
    private int captureLimitCount;

    protected DerivationRun() {
        // JPA
    }

    public DerivationRun(
            Project project,
            DerivationScopeMode scopeMode,
            String commitSha,
            String baseCommitSha,
            List<String> paths,
            List<String> languages,
            List<String> surfaces,
            String requestedBy,
            Instant requestedAt,
            int adapterCount) {
        this.project = project;
        this.scopeMode = scopeMode;
        this.commitSha = commitSha;
        this.baseCommitSha = baseCommitSha;
        this.paths = paths == null ? List.of() : List.copyOf(paths);
        this.languages = List.copyOf(languages);
        this.surfaces = List.copyOf(surfaces);
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.adapterCount = adapterCount;
    }

    public Project getProject() {
        return project;
    }

    public DerivationScopeMode getScopeMode() {
        return scopeMode;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getBaseCommitSha() {
        return baseCommitSha;
    }

    public List<String> getPaths() {
        return paths == null ? List.of() : List.copyOf(paths);
    }

    public List<String> getLanguages() {
        return languages == null ? List.of() : List.copyOf(languages);
    }

    public List<String> getSurfaces() {
        return surfaces == null ? List.of() : List.copyOf(surfaces);
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public int getAdapterCount() {
        return adapterCount;
    }

    public int getFactCount() {
        return factCount;
    }

    public int getCaptureLimitCount() {
        return captureLimitCount;
    }

    public void setResultCounts(int factCount, int captureLimitCount) {
        this.factCount = factCount;
        this.captureLimitCount = captureLimitCount;
    }
}
