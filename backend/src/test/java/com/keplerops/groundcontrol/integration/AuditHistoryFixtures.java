package com.keplerops.groundcontrol.integration;

import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Teardown for {@link AuditHistoryIntegrationTest}.
 *
 * Split out under issue #1467 for the 500-LOC limit (docs/CODING_STANDARDS.md).
 * The test class itself cannot be divided -- its cases are one ordered sequence
 * sharing database state -- but this fixed teardown is not part of that
 * sequence, so it lives here instead.
 */
final class AuditHistoryFixtures {

    private AuditHistoryFixtures() {}

    static void deleteAuditScopedRows(DataSource dataSource) throws SQLException {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM traceability_link_audit WHERE id IN "
                    + "(SELECT id FROM traceability_link WHERE requirement_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%'))");
            stmt.executeUpdate("DELETE FROM traceability_link WHERE requirement_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%')");
            stmt.executeUpdate("DELETE FROM requirement_relation_audit WHERE id IN "
                    + "(SELECT id FROM requirement_relation WHERE source_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%'))");
            stmt.executeUpdate("DELETE FROM requirement_relation WHERE source_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%')");
            stmt.executeUpdate(
                    "DELETE FROM requirement_audit WHERE id IN (SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%')");
            stmt.executeUpdate("DELETE FROM requirement WHERE uid LIKE 'AUDIT-%'");
        }
    }
}
