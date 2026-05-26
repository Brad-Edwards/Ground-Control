package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchIntake;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchIntakeRepository extends JpaRepository<ResearchIntake, UUID> {

    Optional<ResearchIntake> findByProjectId(UUID projectId);

    boolean existsByProjectId(UUID projectId);
}
