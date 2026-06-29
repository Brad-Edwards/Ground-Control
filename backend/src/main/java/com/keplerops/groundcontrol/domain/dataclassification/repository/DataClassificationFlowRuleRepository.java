package com.keplerops.groundcontrol.domain.dataclassification.repository;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationFlowRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataClassificationFlowRuleRepository extends JpaRepository<DataClassificationFlowRule, UUID> {

    List<DataClassificationFlowRule> findByLatticeIdOrderByFromLabelKeyAscToLabelKeyAsc(UUID latticeId);

    List<DataClassificationFlowRule> findByProjectId(UUID projectId);
}
