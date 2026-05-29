package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
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
import java.util.List;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(
        name = "methodology_profile",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "profile_key", "version"}))
public class MethodologyProfile extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "profile_key", nullable = false, length = 100)
    private String profileKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MethodologyFamily family;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "input_schema", columnDefinition = "TEXT")
    private Map<String, Object> inputSchema;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "output_schema", columnDefinition = "TEXT")
    private Map<String, Object> outputSchema;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "treatment_strategy_vocabulary", columnDefinition = "TEXT")
    private Map<String, Object> treatmentStrategyVocabulary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MethodologyProfileStatus status = MethodologyProfileStatus.ACTIVE;

    @Convert(converter = JacksonTextCollectionConverters.CrosswalkEntryListConverter.class)
    @Column(name = "crosswalk_entries", columnDefinition = "TEXT")
    private List<CrosswalkEntry> crosswalkEntries;

    protected MethodologyProfile() {
        // JPA
    }

    public MethodologyProfile(
            Project project, String profileKey, String name, String version, MethodologyFamily family) {
        this.project = project;
        this.profileKey = profileKey;
        this.name = name;
        this.version = version;
        this.family = family;
    }

    public Project getProject() {
        return project;
    }

    public String getProfileKey() {
        return profileKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public MethodologyFamily getFamily() {
        return family;
    }

    public void setFamily(MethodologyFamily family) {
        this.family = family;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(Map<String, Object> outputSchema) {
        this.outputSchema = outputSchema;
    }

    public Map<String, Object> getTreatmentStrategyVocabulary() {
        return treatmentStrategyVocabulary;
    }

    public void setTreatmentStrategyVocabulary(Map<String, Object> treatmentStrategyVocabulary) {
        this.treatmentStrategyVocabulary = treatmentStrategyVocabulary;
    }

    public MethodologyProfileStatus getStatus() {
        return status;
    }

    public void setStatus(MethodologyProfileStatus status) {
        this.status = status;
    }

    public List<CrosswalkEntry> getCrosswalkEntries() {
        return crosswalkEntries;
    }

    public void setCrosswalkEntries(List<CrosswalkEntry> crosswalkEntries) {
        this.crosswalkEntries = crosswalkEntries;
    }
}
