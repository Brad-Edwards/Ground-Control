package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-F006 — one methodology-selection record for a research run. At most
 * one selection per run is active (supersededAt IS NULL) at any given time;
 * selecting a different methodology key (or the same key after sources have
 * been recorded) supersedes the prior active row and creates a new one.
 *
 * <p>The partial unique index {@code uq_methodology_selection_active_per_run}
 * on {@code (research_run_id) WHERE superseded_at IS NULL} enforces the
 * single-active invariant at the database level.
 */
@Entity
@Audited
@Table(name = "research_run_methodology_selection")
public class ResearchRunMethodologySelection extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Column(name = "method_key", nullable = false, length = 200)
    private String methodKey;

    @Column(name = "method_label", length = 500)
    private String methodLabel;

    @Column(name = "profile_version", length = 100)
    private String profileVersion;

    @Column(name = "catalog_version", length = 100)
    private String catalogVersion;

    @Column(length = 200)
    private String actor;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected ResearchRunMethodologySelection() {
        // JPA
    }

    public ResearchRunMethodologySelection(ResearchRun researchRun, String methodKey, String actor) {
        this.researchRun = researchRun;
        this.methodKey = methodKey;
        this.actor = actor;
    }

    public void supersede() {
        this.supersededAt = Instant.now();
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public String getMethodKey() {
        return methodKey;
    }

    public void setMethodLabel(String methodLabel) {
        this.methodLabel = methodLabel;
    }

    public String getMethodLabel() {
        return methodLabel;
    }

    public void setProfileVersion(String profileVersion) {
        this.profileVersion = profileVersion;
    }

    public String getProfileVersion() {
        return profileVersion;
    }

    public void setCatalogVersion(String catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public String getCatalogVersion() {
        return catalogVersion;
    }

    public String getActor() {
        return actor;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }
}
