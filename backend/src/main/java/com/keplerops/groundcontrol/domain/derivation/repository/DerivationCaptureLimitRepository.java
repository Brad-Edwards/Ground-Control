package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.DerivationCaptureLimit;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DerivationCaptureLimitRepository extends JpaRepository<DerivationCaptureLimit, UUID> {

    List<DerivationCaptureLimit> findByProjectIdOrderByCapturedAtDesc(UUID projectId);

    List<DerivationCaptureLimit> findByProjectIdAndReasonOrderByCapturedAtDesc(
            UUID projectId, CaptureLimitReason reason);

    List<DerivationCaptureLimit> findByProjectIdAndDerivationRunIdOrderByCapturedAtDesc(
            UUID projectId, UUID derivationRunId);

    List<DerivationCaptureLimit> findByProjectIdAndDerivationRunIdAndReasonOrderByCapturedAtDesc(
            UUID projectId, UUID derivationRunId, CaptureLimitReason reason);
}
