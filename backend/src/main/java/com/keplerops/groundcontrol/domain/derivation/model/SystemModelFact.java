package com.keplerops.groundcontrol.domain.derivation.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "system_model_fact")
public class SystemModelFact extends BaseEntity {

    public static final String SCHEMA_VERSION = "system-model-fact/v1";

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "derivation_run_id", nullable = false)
    private DerivationRun derivationRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_kind", nullable = false, length = 40)
    private SystemModelFactKind factKind;

    @Column(name = "schema_version", nullable = false, length = 40)
    private String schemaVersion = SCHEMA_VERSION;

    @Column(name = "fact_key", nullable = false, length = 200)
    private String factKey;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Convert(converter = StringObjectMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> payload;

    @Column(name = "adapter_id", nullable = false, length = 100)
    private String adapterId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(name = "tool_version", nullable = false, length = 100)
    private String toolVersion;

    @Column(name = "ruleset_name", nullable = false, length = 200)
    private String rulesetName;

    @Column(name = "ruleset_version", nullable = false, length = 100)
    private String rulesetVersion;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "derived_at", nullable = false)
    private Instant derivedAt;

    protected SystemModelFact() {
        // JPA
    }

    public SystemModelFact(Project project, DerivationRun derivationRun, DerivedSystemModelFact fact) {
        var provenance = fact.provenance();
        this.project = project;
        this.derivationRun = derivationRun;
        this.factKind = fact.factKind();
        this.factKey = fact.factKey();
        this.label = fact.label();
        this.summary = fact.summary();
        this.sourcePath = fact.sourcePath();
        this.payload = fact.payload();
        this.adapterId = provenance.adapterId();
        this.toolName = provenance.toolName();
        this.toolVersion = provenance.toolVersion();
        this.rulesetName = provenance.rulesetName();
        this.rulesetVersion = provenance.rulesetVersion();
        this.commitSha = provenance.commitSha();
        this.derivedAt = provenance.derivedAt();
    }

    public Project getProject() {
        return project;
    }

    public DerivationRun getDerivationRun() {
        return derivationRun;
    }

    public SystemModelFactKind getFactKind() {
        return factKind;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getFactKey() {
        return factKey;
    }

    public String getLabel() {
        return label;
    }

    public String getSummary() {
        return summary;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public Map<String, Object> getPayload() {
        return payload == null ? Map.of() : Map.copyOf(payload);
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public String getRulesetName() {
        return rulesetName;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public Instant getDerivedAt() {
        return derivedAt;
    }
}
