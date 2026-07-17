package com.keplerops.groundcontrol.domain.audit.repository;

import com.keplerops.groundcontrol.domain.audit.GroundControlRevisionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read access to the {@code revinfo} table (ADR-084 §5). The canonical as-of coordinate is the
 * Envers revision number; this repository is the single query surface over it. Consumers resolve
 * a revision through {@link com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver}
 * rather than issuing their own {@code revinfo} queries, so this stays the one place the
 * inclusive-boundary semantics are defined.
 */
public interface RevisionRepository extends JpaRepository<GroundControlRevisionEntity, Integer> {

    /**
     * Greatest revision number whose timestamp is at or before {@code asOfMillis} (inclusive
     * boundary). Empty when no revision satisfies the predicate — including when {@code revinfo}
     * has no rows at all.
     */
    @Query("SELECT MAX(r.id) FROM GroundControlRevisionEntity r WHERE r.timestamp <= :asOfMillis")
    Optional<Integer> findGreatestRevisionAtOrBefore(@Param("asOfMillis") long asOfMillis);

    /** Greatest revision number recorded, or empty when {@code revinfo} has no rows. */
    @Query("SELECT MAX(r.id) FROM GroundControlRevisionEntity r")
    Optional<Integer> findGreatestRevision();
}
