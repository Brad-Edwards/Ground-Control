package com.keplerops.groundcontrol.domain.workflowexecution.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only persistence for {@link OperatorSignalAudit} (GC-O009 (b)). Writes are inserts only; the
 * reads are project- and workflow-scoped, newest first, for the gate-authority trail the console
 * (GC-Q016) and operators consult. No update/delete surface is exposed beyond the inherited defaults —
 * the audit log is evidence, never edited by product code.
 */
public interface OperatorSignalAuditRepository extends JpaRepository<OperatorSignalAudit, UUID> {

    /** Recent audit rows for one execution, newest first. */
    List<OperatorSignalAudit> findByWorkflowIdOrderByCreatedAtDesc(String workflowId, Pageable pageable);

    /** Recent audit rows across one project, newest first. */
    List<OperatorSignalAudit> findByProjectOrderByCreatedAtDesc(String project, Pageable pageable);
}
