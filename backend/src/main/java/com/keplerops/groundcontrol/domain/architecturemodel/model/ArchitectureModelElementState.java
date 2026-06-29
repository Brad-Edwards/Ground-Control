package com.keplerops.groundcontrol.domain.architecturemodel.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementStateCommand;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelProvenanceSource;
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
import jakarta.persistence.UniqueConstraint;
import java.util.Map;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(
        name = "architecture_model_element_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"snapshot_id", "stable_key"}))
public class ArchitectureModelElementState extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private ArchitectureModelSnapshot snapshot;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "element_id", nullable = false)
    private ArchitectureModelElement element;

    @Column(name = "stable_key", nullable = false, length = 200)
    private String stableKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "element_kind", nullable = false, length = 40)
    private ArchitectureModelElementKind elementKind;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(name = "trust_boundary_key", length = 120)
    private String trustBoundaryKey;

    @Column(name = "data_classification_key", length = 120)
    private String dataClassificationKey;

    @Column(name = "flow_source_stable_key", length = 200)
    private String flowSourceStableKey;

    @Column(name = "flow_target_stable_key", length = 200)
    private String flowTargetStableKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_direction", length = 20)
    private ArchitectureFlowDirection flowDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance_source", nullable = false, length = 20)
    private ArchitectureModelProvenanceSource provenanceSource;

    @Column(name = "provenance_key", nullable = false, length = 200)
    private String provenanceKey;

    @Column(name = "adapter_id", length = 100)
    private String adapterId;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "tool_version", length = 100)
    private String toolVersion;

    @Column(name = "ruleset_name", length = 200)
    private String rulesetName;

    @Column(name = "ruleset_version", length = 100)
    private String rulesetVersion;

    @Column(name = "derivation_run_id")
    private UUID derivationRunId;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Convert(converter = StringObjectMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> metadata;

    protected ArchitectureModelElementState() {
        // JPA
    }

    public ArchitectureModelElementState(
            Project project,
            ArchitectureModelSnapshot snapshot,
            ArchitectureModelElement element,
            ArchitectureModelElementStateCommand command) {
        this.project = project;
        this.snapshot = snapshot;
        this.element = element;
        this.stableKey = command.stableKey();
        this.elementKind = command.elementKind();
        this.label = command.label();
        this.summary = command.summary();
        this.sourcePath = command.sourcePath();
        this.trustBoundaryKey = command.trustBoundaryKey();
        this.dataClassificationKey = command.dataClassificationKey();
        this.flowSourceStableKey = command.flowSourceStableKey();
        this.flowTargetStableKey = command.flowTargetStableKey();
        this.flowDirection = command.flowDirection();
        this.provenanceSource = command.provenanceSource();
        this.provenanceKey = command.provenanceKey();
        this.adapterId = command.adapterId();
        this.toolName = command.toolName();
        this.toolVersion = command.toolVersion();
        this.rulesetName = command.rulesetName();
        this.rulesetVersion = command.rulesetVersion();
        this.derivationRunId = command.derivationRunId();
        this.commitSha = command.commitSha();
        this.metadata = command.metadata();
    }

    public Project getProject() {
        return project;
    }

    public ArchitectureModelSnapshot getSnapshot() {
        return snapshot;
    }

    public ArchitectureModelElement getElement() {
        return element;
    }

    public String getStableKey() {
        return stableKey;
    }

    public ArchitectureModelElementKind getElementKind() {
        return elementKind;
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

    public String getTrustBoundaryKey() {
        return trustBoundaryKey;
    }

    public String getDataClassificationKey() {
        return dataClassificationKey;
    }

    public String getFlowSourceStableKey() {
        return flowSourceStableKey;
    }

    public String getFlowTargetStableKey() {
        return flowTargetStableKey;
    }

    public ArchitectureFlowDirection getFlowDirection() {
        return flowDirection;
    }

    public ArchitectureModelProvenanceSource getProvenanceSource() {
        return provenanceSource;
    }

    public String getProvenanceKey() {
        return provenanceKey;
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

    public UUID getDerivationRunId() {
        return derivationRunId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
