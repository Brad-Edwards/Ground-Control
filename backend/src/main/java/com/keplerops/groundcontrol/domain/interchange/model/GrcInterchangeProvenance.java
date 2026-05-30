package com.keplerops.groundcontrol.domain.interchange.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.interchange.state.InterchangeEntityKind;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Provenance shadow that preserves client-supplied import-time temporal
 * metadata without overwriting the domain entity timestamps (ADR-045).
 *
 * <p>Keyed by {@code (project_id, entity_kind, external_uid)} so re-importing
 * the same external identifier is idempotent — the importer updates the
 * existing shadow rather than creating a duplicate. The domain entity it
 * shadows is identified by {@code entityId}; no FK is declared because the
 * shadow may outlive deletes (intentional — the import history survives).
 */
@Entity
@Audited
@Table(
        name = "grc_interchange_provenance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "entity_kind", "external_uid"}))
public class GrcInterchangeProvenance extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_kind", nullable = false, length = 40)
    private InterchangeEntityKind entityKind;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "external_uid", nullable = false, length = 120)
    private String externalUid;

    @Column(name = "source_system", length = 120)
    private String sourceSystem;

    @Column(name = "source_created_at")
    private Instant sourceCreatedAt;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "imported_by", length = 100)
    private String importedBy;

    protected GrcInterchangeProvenance() {
        // JPA
    }

    public GrcInterchangeProvenance(
            Project project,
            InterchangeEntityKind entityKind,
            UUID entityId,
            String externalUid,
            String sourceSystem,
            Instant sourceCreatedAt,
            Instant sourceUpdatedAt,
            Instant importedAt,
            String importedBy) {
        this.project = project;
        this.entityKind = entityKind;
        this.entityId = entityId;
        this.externalUid = externalUid;
        this.sourceSystem = sourceSystem;
        this.sourceCreatedAt = sourceCreatedAt;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.importedAt = importedAt;
        this.importedBy = importedBy;
    }

    public Project getProject() {
        return project;
    }

    public InterchangeEntityKind getEntityKind() {
        return entityKind;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public String getExternalUid() {
        return externalUid;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public Instant getSourceCreatedAt() {
        return sourceCreatedAt;
    }

    public void setSourceCreatedAt(Instant sourceCreatedAt) {
        this.sourceCreatedAt = sourceCreatedAt;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public void setSourceUpdatedAt(Instant sourceUpdatedAt) {
        this.sourceUpdatedAt = sourceUpdatedAt;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public String getImportedBy() {
        return importedBy;
    }

    public void setImportedBy(String importedBy) {
        this.importedBy = importedBy;
    }
}
