package com.keplerops.groundcontrol.domain.packregistry.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.packregistry.state.CatalogStatus;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(
        name = "pack_registry_entry",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_pack_registry_project_pack_version",
                        columnNames = {"project_id", "pack_id", "version"}))
public class PackRegistryEntry extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "pack_id", nullable = false, length = 200)
    private String packId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pack_type", nullable = false, length = 30)
    private PackType packType;

    @Column(length = 200)
    private String publisher;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "source_url", length = 2000)
    private String sourceUrl;

    @Column(length = 128)
    private String checksum;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "signature_info", columnDefinition = "TEXT")
    private Map<String, Object> signatureInfo;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> compatibility;

    @Convert(converter = JacksonTextCollectionConverters.PackDependencyListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<PackDependency> dependencies;

    @Convert(converter = JacksonTextCollectionConverters.RegisteredControlPackEntryListConverter.class)
    @Column(name = "control_pack_entries", columnDefinition = "TEXT")
    private List<RegisteredControlPackEntry> controlPackEntries;

    @Convert(converter = JacksonTextCollectionConverters.RegisteredThreatRuleListConverter.class)
    @Column(name = "threat_rule_entries", columnDefinition = "TEXT")
    private List<RegisteredThreatRule> threatRuleEntries;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> provenance;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "registry_metadata", columnDefinition = "TEXT")
    private Map<String, Object> registryMetadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "catalog_status", nullable = false, length = 20)
    private CatalogStatus catalogStatus = CatalogStatus.AVAILABLE;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected PackRegistryEntry() {}

    public PackRegistryEntry(Project project, String packId, PackType packType, String version) {
        this.project = project;
        this.packId = packId;
        this.packType = packType;
        this.version = version;
        this.catalogStatus = CatalogStatus.AVAILABLE;
        this.registeredAt = Instant.now();
    }

    public void transitionCatalogStatus(CatalogStatus newStatus) {
        if (!catalogStatus.canTransitionTo(newStatus)) {
            throw new DomainValidationException(
                    String.format("Cannot transition catalog status from %s to %s", catalogStatus, newStatus),
                    "validation_error",
                    Map.of("current_status", catalogStatus.name(), "target_status", newStatus.name()));
        }
        this.catalogStatus = newStatus;
    }

    public Project getProject() {
        return project;
    }

    public String getPackId() {
        return packId;
    }

    public PackType getPackType() {
        return packType;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public Map<String, Object> getSignatureInfo() {
        return signatureInfo;
    }

    public void setSignatureInfo(Map<String, Object> signatureInfo) {
        this.signatureInfo = signatureInfo;
    }

    public Map<String, Object> getCompatibility() {
        return compatibility;
    }

    public void setCompatibility(Map<String, Object> compatibility) {
        this.compatibility = compatibility;
    }

    public List<PackDependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<PackDependency> dependencies) {
        this.dependencies = dependencies;
    }

    public List<RegisteredControlPackEntry> getControlPackEntries() {
        return controlPackEntries;
    }

    public void setControlPackEntries(List<RegisteredControlPackEntry> controlPackEntries) {
        this.controlPackEntries = controlPackEntries;
    }

    public List<RegisteredThreatRule> getThreatRuleEntries() {
        return threatRuleEntries;
    }

    public void setThreatRuleEntries(List<RegisteredThreatRule> threatRuleEntries) {
        this.threatRuleEntries = threatRuleEntries;
    }

    public Map<String, Object> getProvenance() {
        return provenance;
    }

    public void setProvenance(Map<String, Object> provenance) {
        this.provenance = provenance;
    }

    public Map<String, Object> getRegistryMetadata() {
        return registryMetadata;
    }

    public void setRegistryMetadata(Map<String, Object> registryMetadata) {
        this.registryMetadata = registryMetadata;
    }

    public CatalogStatus getCatalogStatus() {
        return catalogStatus;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
