package com.keplerops.groundcontrol.domain.decisions.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Recorded decision analysis per GC-W011: inputs, model, simulation
 * parameters, results, chosen alternative, and rationale for selection.
 *
 * <p>Lives in {@code domain/decisions/} (plural) so it does not collide with
 * the {@code domain/audit/} Hibernate Envers infrastructure package and so the
 * REST surface {@code /api/v1/decisions/**} does not clash with the
 * gc_post_decision_record workflow gate.
 *
 * <p>{@code inputs} captures named estimate inputs with attribution; estimator
 * identity flows through ActorHolder per ADR-033. {@code simulationParameters}
 * captures model name, seed, iterations and any other model-specific knobs.
 * {@code results} captures the simulation output summary (e.g. quantiles).
 * {@code alternatives} captures the set considered; {@code chosenAlternative}
 * names the one selected; {@code rationale} captures the reasoning.
 */
@Entity
@Audited
@Table(name = "decision_analysis_record", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class DecisionAnalysisRecord extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 30)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "inputs", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    private Map<String, Object> inputs;

    @Column(name = "simulation_parameters", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    private Map<String, Object> simulationParameters;

    @Column(name = "results", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    private Map<String, Object> results;

    @Column(name = "alternatives", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.StringListConverter.class)
    private List<String> alternatives;

    @Column(name = "chosen_alternative", length = 200)
    private String chosenAlternative;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected DecisionAnalysisRecord() {
        // JPA
    }

    public DecisionAnalysisRecord(Project project, String uid, String title, String modelName) {
        if (project == null) {
            throw new DomainValidationException("project must not be null");
        }
        if (uid == null || uid.isBlank()) {
            throw new DomainValidationException("uid must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("title must not be blank");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new DomainValidationException("modelName must not be blank");
        }
        this.project = project;
        this.uid = uid;
        this.title = title;
        this.modelName = modelName;
    }

    public Project getProject() {
        return project;
    }

    public String getUid() {
        return uid;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("title must not be blank");
        }
        this.title = title;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new DomainValidationException("modelName must not be blank");
        }
        this.modelName = modelName;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Map<String, Object> getInputs() {
        return inputs == null ? Map.of() : inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public Map<String, Object> getSimulationParameters() {
        return simulationParameters == null ? Map.of() : simulationParameters;
    }

    public void setSimulationParameters(Map<String, Object> p) {
        this.simulationParameters = p;
    }

    public Map<String, Object> getResults() {
        return results == null ? Map.of() : results;
    }

    public void setResults(Map<String, Object> results) {
        this.results = results;
    }

    public List<String> getAlternatives() {
        return alternatives == null ? List.of() : alternatives;
    }

    public void setAlternatives(List<String> alternatives) {
        this.alternatives = alternatives;
    }

    public String getChosenAlternative() {
        return chosenAlternative;
    }

    public void setChosenAlternative(String chosenAlternative) {
        this.chosenAlternative = chosenAlternative;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public String toString() {
        return uid + ": " + title;
    }
}
