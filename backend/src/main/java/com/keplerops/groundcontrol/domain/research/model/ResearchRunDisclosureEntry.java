package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-N013 / ADR-068 §4 — one declared item within a {@link
 * ResearchRunDisclosure}: either an AI-generated portion of the manuscript or an
 * unresolved uncertainty. An {@code UNRESOLVED_UNCERTAINTY} entry carries an
 * {@link DisclosureUncertaintyCategory}. Entries may cross-reference the
 * rationale, decision-log, and review-comment rows that motivate them.
 */
@Entity
@Audited
@Table(name = "research_run_disclosure_entry")
public class ResearchRunDisclosureEntry extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "disclosure_id", nullable = false)
    private ResearchRunDisclosure disclosure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DisclosureEntryFamily family;

    @Enumerated(EnumType.STRING)
    @Column(name = "uncertainty_category", length = 30)
    private DisclosureUncertaintyCategory uncertaintyCategory;

    @Column(name = "section_key", length = 200)
    private String sectionKey;

    @Column(length = 500)
    private String locator;

    @Column(name = "model_label", length = 200)
    private String modelLabel;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Column(name = "rationale_entry_id")
    private UUID rationaleEntryId;

    @Column(name = "decision_log_id")
    private UUID decisionLogId;

    @Column(name = "review_comment_id")
    private UUID reviewCommentId;

    @Column(length = 200)
    private String actor;

    protected ResearchRunDisclosureEntry() {
        // JPA
    }

    public ResearchRunDisclosureEntry(
            ResearchRunDisclosure disclosure, DisclosureEntryFamily family, String summary, String actor) {
        if (disclosure == null) {
            throw new DomainValidationException(
                    "Disclosure must not be null", "invalid_research_disclosure_entry", Map.of());
        }
        if (family == null) {
            throw new DomainValidationException(
                    "Family must not be null", "invalid_research_disclosure_entry", Map.of());
        }
        if (summary == null || summary.isBlank()) {
            throw new DomainValidationException(
                    "Summary must not be blank", "invalid_research_disclosure_entry", Map.of());
        }
        this.disclosure = disclosure;
        this.family = family;
        this.summary = summary;
        this.actor = actor;
    }

    public void setUncertaintyCategory(DisclosureUncertaintyCategory uncertaintyCategory) {
        this.uncertaintyCategory = uncertaintyCategory;
    }

    public void setSectionKey(String sectionKey) {
        this.sectionKey = sectionKey;
    }

    public void setLocator(String locator) {
        this.locator = locator;
    }

    public void setModelLabel(String modelLabel) {
        this.modelLabel = modelLabel;
    }

    public void setRationaleEntryId(UUID rationaleEntryId) {
        this.rationaleEntryId = rationaleEntryId;
    }

    public void setDecisionLogId(UUID decisionLogId) {
        this.decisionLogId = decisionLogId;
    }

    public void setReviewCommentId(UUID reviewCommentId) {
        this.reviewCommentId = reviewCommentId;
    }

    public ResearchRunDisclosure getDisclosure() {
        return disclosure;
    }

    public DisclosureEntryFamily getFamily() {
        return family;
    }

    public DisclosureUncertaintyCategory getUncertaintyCategory() {
        return uncertaintyCategory;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public String getLocator() {
        return locator;
    }

    public String getModelLabel() {
        return modelLabel;
    }

    public String getSummary() {
        return summary;
    }

    public UUID getRationaleEntryId() {
        return rationaleEntryId;
    }

    public UUID getDecisionLogId() {
        return decisionLogId;
    }

    public UUID getReviewCommentId() {
        return reviewCommentId;
    }

    public String getActor() {
        return actor;
    }
}
