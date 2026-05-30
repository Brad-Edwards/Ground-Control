package com.keplerops.groundcontrol.domain.compliance.repository;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplianceFrameworkMappingRepository extends JpaRepository<ComplianceFrameworkMapping, UUID> {

    Optional<ComplianceFrameworkMapping> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByIdAndProjectId(UUID id, UUID projectId);

    List<ComplianceFrameworkMapping> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    @Query("SELECT m FROM ComplianceFrameworkMapping m"
            + " WHERE m.project.id = :projectId AND m.framework = :framework"
            + " ORDER BY m.frameworkElement ASC")
    List<ComplianceFrameworkMapping> findByProjectIdAndFramework(
            @Param("projectId") UUID projectId, @Param("framework") ComplianceFrameworkIdentifier framework);

    @Query("SELECT m FROM ComplianceFrameworkMapping m"
            + " WHERE m.project.id = :projectId AND m.requirement.id = :requirementId")
    List<ComplianceFrameworkMapping> findByProjectIdAndRequirementId(
            @Param("projectId") UUID projectId, @Param("requirementId") UUID requirementId);

    @Query("SELECT m FROM ComplianceFrameworkMapping m"
            + " WHERE m.project.id = :projectId AND m.control.id = :controlId")
    List<ComplianceFrameworkMapping> findByProjectIdAndControlId(
            @Param("projectId") UUID projectId, @Param("controlId") UUID controlId);

    // Service-layer conflict guards. Tuple = (endpoint, framework, element).
    boolean existsByRequirementIdAndFrameworkAndFrameworkElement(
            UUID requirementId, ComplianceFrameworkIdentifier framework, String frameworkElement);

    boolean existsByControlIdAndFrameworkAndFrameworkElement(
            UUID controlId, ComplianceFrameworkIdentifier framework, String frameworkElement);
}
