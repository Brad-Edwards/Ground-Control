package com.keplerops.groundcontrol.domain.assets.repository;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObservationRepository extends JpaRepository<Observation, UUID> {

    @Query("SELECT o FROM Observation o JOIN FETCH o.asset WHERE o.id = :id")
    Optional<Observation> findByIdWithAsset(UUID id);

    @Query("SELECT o FROM Observation o JOIN FETCH o.asset" + " WHERE o.id = :id AND o.asset.project.id = :projectId")
    Optional<Observation> findByIdWithAssetAndProjectId(UUID id, UUID projectId);

    @Query("SELECT o FROM Observation o JOIN FETCH o.asset WHERE o.asset.id = :assetId ORDER BY o.observedAt DESC")
    List<Observation> findByAssetId(UUID assetId);

    @Query("SELECT o FROM Observation o JOIN FETCH o.asset"
            + " WHERE o.asset.project.id = :projectId ORDER BY o.observedAt DESC")
    List<Observation> findByProjectId(@Param("projectId") UUID projectId);

    @Query(
            "SELECT o FROM Observation o JOIN FETCH o.asset WHERE o.asset.id = :assetId AND o.category = :category ORDER BY o.observedAt DESC")
    List<Observation> findByAssetIdAndCategory(UUID assetId, ObservationCategory category);

    @Query(
            "SELECT o FROM Observation o JOIN FETCH o.asset WHERE o.asset.id = :assetId AND o.observationKey = :key ORDER BY o.observedAt DESC")
    List<Observation> findByAssetIdAndKey(UUID assetId, String key);

    @Query(
            "SELECT o FROM Observation o JOIN FETCH o.asset WHERE o.asset.id = :assetId AND o.category = :category AND o.observationKey = :key ORDER BY o.observedAt DESC")
    List<Observation> findByAssetIdAndCategoryAndKey(UUID assetId, ObservationCategory category, String key);

    boolean existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
            UUID assetId, ObservationCategory category, String observationKey, java.time.Instant observedAt);

    @Query("SELECT (COUNT(o) > 0) FROM Observation o WHERE o.id = :id AND o.asset.project.id = :projectId")
    boolean existsByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("SELECT o FROM Observation o JOIN FETCH o.asset WHERE o.asset.id = :assetId "
            + "AND (o.expiresAt IS NULL OR o.expiresAt > :now) "
            + "AND o.observedAt = (SELECT MAX(o2.observedAt) FROM Observation o2 "
            + "WHERE o2.asset.id = :assetId AND o2.category = o.category AND o2.observationKey = o.observationKey "
            + "AND (o2.expiresAt IS NULL OR o2.expiresAt > :now)) "
            + "ORDER BY o.category, o.observationKey")
    List<Observation> findLatestByAssetId(UUID assetId, java.time.Instant now);

    @Query("SELECT o FROM Observation o JOIN FETCH o.asset" + " WHERE o.id IN :ids AND o.asset.project.id = :projectId")
    List<Observation> findAllByIdInAndProjectId(@Param("ids") List<UUID> ids, @Param("projectId") UUID projectId);
}
