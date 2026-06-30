package com.keplerops.groundcontrol.domain.dataclassification.repository;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataClassificationLabelRepository extends JpaRepository<DataClassificationLabel, UUID> {

    List<DataClassificationLabel> findByLatticeIdOrderByLabelKey(UUID latticeId);

    List<DataClassificationLabel> findByProjectId(UUID projectId);
}
