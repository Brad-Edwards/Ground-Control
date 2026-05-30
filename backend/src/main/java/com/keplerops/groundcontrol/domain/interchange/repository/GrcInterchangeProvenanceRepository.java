package com.keplerops.groundcontrol.domain.interchange.repository;

import com.keplerops.groundcontrol.domain.interchange.model.GrcInterchangeProvenance;
import com.keplerops.groundcontrol.domain.interchange.state.InterchangeEntityKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrcInterchangeProvenanceRepository extends JpaRepository<GrcInterchangeProvenance, UUID> {

    Optional<GrcInterchangeProvenance> findByProjectIdAndEntityKindAndExternalUid(
            UUID projectId, InterchangeEntityKind entityKind, String externalUid);

    List<GrcInterchangeProvenance> findByProjectIdAndEntityKind(UUID projectId, InterchangeEntityKind entityKind);
}
