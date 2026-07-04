package com.keplerops.groundcontrol.domain.grcassessment.repository;

import com.keplerops.groundcontrol.domain.grcassessment.model.GrcAssessmentRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrcAssessmentRunRepository extends JpaRepository<GrcAssessmentRun, UUID> {

    Optional<GrcAssessmentRun> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<GrcAssessmentRun> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

    List<GrcAssessmentRun> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);
}
