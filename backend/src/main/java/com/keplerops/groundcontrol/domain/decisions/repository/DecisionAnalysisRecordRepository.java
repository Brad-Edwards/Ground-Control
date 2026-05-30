package com.keplerops.groundcontrol.domain.decisions.repository;

import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionAnalysisRecordRepository extends JpaRepository<DecisionAnalysisRecord, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<DecisionAnalysisRecord> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<DecisionAnalysisRecord> findByProjectIdAndUid(UUID projectId, String uid);

    List<DecisionAnalysisRecord> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
