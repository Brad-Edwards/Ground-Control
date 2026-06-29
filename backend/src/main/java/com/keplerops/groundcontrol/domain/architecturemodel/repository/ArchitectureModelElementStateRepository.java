package com.keplerops.groundcontrol.domain.architecturemodel.repository;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArchitectureModelElementStateRepository extends JpaRepository<ArchitectureModelElementState, UUID> {

    List<ArchitectureModelElementState> findBySnapshotIdOrderByStableKey(UUID snapshotId);

    @Query(
            value = "SELECT s.* FROM architecture_model_element_state s "
                    + "WHERE s.snapshot_id = ("
                    + "  SELECT snap.id FROM architecture_model_snapshot snap "
                    + "  WHERE snap.project_id = :projectId "
                    + "  ORDER BY snap.created_at DESC, snap.id DESC LIMIT 1"
                    + ") ORDER BY s.stable_key",
            nativeQuery = true)
    List<ArchitectureModelElementState> findLatestSnapshotStatesByProjectId(@Param("projectId") UUID projectId);

    @Query(
            value = "SELECT s.* FROM architecture_model_element_state s "
                    + "JOIN architecture_model_snapshot snap ON snap.id = s.snapshot_id "
                    + "WHERE s.project_id = :projectId AND s.element_id = :elementId "
                    + "ORDER BY snap.created_at DESC, snap.id DESC LIMIT 1",
            nativeQuery = true)
    Optional<ArchitectureModelElementState> findLatestStateByElementIdAndProjectId(
            @Param("elementId") UUID elementId, @Param("projectId") UUID projectId);
}
