package com.keplerops.groundcontrol.infrastructure.compliance;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that purges audit records older than the configured retention period.
 *
 * <p>The set of audit tables to purge is derived from the database catalog on every run
 * ({@link #discoverAuditTables()}), not hand-maintained: every Envers {@code *_audit} table
 * declares {@code rev INTEGER NOT NULL REFERENCES revinfo(rev)}, so the catalog query "tables
 * with a foreign key to revinfo(rev)" is exactly the audit-table set, automatically, including
 * tables added or dropped by later migrations. A previous hand-maintained list drifted both ways
 * — it named tables a migration had already dropped (crashing this job with a SQL error on the
 * first one) and omitted tables that existed — silently, because nothing forced the list and the
 * schema to agree. Catalog names are never user input but are still identifier-validated before
 * interpolation (defense in depth, mirroring the AGE graph-name allowlist).
 */
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);
    private static final int BATCH_SIZE = 1000;

    /** Postgres unquoted identifiers are lower-cased snake_case; nothing else may reach the DELETE SQL. */
    private static final Pattern AUDIT_TABLE_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * Read from {@code pg_catalog} rather than {@code information_schema}: the latter's
     * {@code constraint_column_usage} view only exposes columns of tables owned by a currently
     * enabled role, so an application role that has DML on the audit tables but does not own them
     * would silently see a short list and under-purge. {@code pg_catalog} applies no such
     * ownership filter. Schema is {@code current_schema()} to match the unqualified
     * {@code DELETE}s below, which resolve through the same {@code search_path}.
     */
    private static final String AUDIT_TABLE_CATALOG_QUERY = "SELECT DISTINCT child.relname "
            + "FROM pg_catalog.pg_constraint con "
            + "JOIN pg_catalog.pg_class child ON child.oid = con.conrelid "
            + "JOIN pg_catalog.pg_class parent ON parent.oid = con.confrelid "
            + "JOIN pg_catalog.pg_namespace nsp ON nsp.oid = child.relnamespace "
            + "WHERE con.contype = 'f' "
            + "  AND parent.relname = 'revinfo' "
            + "  AND nsp.nspname = current_schema() "
            + "  AND EXISTS ("
            + "    SELECT 1 FROM unnest(con.confkey) AS referenced(attnum) "
            + "    JOIN pg_catalog.pg_attribute att "
            + "      ON att.attrelid = con.confrelid AND att.attnum = referenced.attnum "
            + "    WHERE att.attname = 'rev') "
            + "ORDER BY child.relname";

    private final AuditRetentionProperties properties;
    private final EntityManager entityManager;

    public AuditRetentionJob(AuditRetentionProperties properties, EntityManager entityManager) {
        this.properties = properties;
        this.entityManager = entityManager;
    }

    @Scheduled(cron = "${groundcontrol.compliance.audit-retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldAuditRecords() {
        long cutoffMs = System.currentTimeMillis() - ((long) properties.retentionDays() * 24 * 60 * 60 * 1000);
        List<String> auditTables = discoverAuditTables();
        int totalAudit = 0;

        // Audit rows before revinfo: every audit table's rev FK requires the referenced revinfo
        // row to still exist at delete time.
        for (String table : auditTables) {
            totalAudit += deleteBatched(table, "rev IN (SELECT rev FROM revinfo WHERE revtstmp < :cutoff)", cutoffMs);
        }

        int revinfo = deleteBatched("revinfo", "revtstmp < :cutoff", cutoffMs);

        log.info(
                "Audit retention cleanup: deleted {} audit rows across {} tables, {} revinfo entries (retention={} days)",
                totalAudit,
                auditTables.size(),
                revinfo,
                properties.retentionDays());
    }

    /**
     * Every table with a foreign key to {@code revinfo(rev)} — the live audit-table set, derived
     * from the catalog rather than hand-maintained. Each returned name is identifier-validated
     * before any caller may interpolate it into SQL. Visible for testing.
     */
    @SuppressWarnings("unchecked")
    public List<String> discoverAuditTables() {
        List<Object> rawNames =
                entityManager.createNativeQuery(AUDIT_TABLE_CATALOG_QUERY).getResultList();
        List<String> validated = new ArrayList<>(rawNames.size());
        for (Object rawName : rawNames) {
            validated.add(validateAuditTableName(String.valueOf(rawName)));
        }
        return validated;
    }

    private static String validateAuditTableName(String name) {
        if (name == null || !AUDIT_TABLE_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalStateException("Invalid audit table name from catalog: " + name);
        }
        return name;
    }

    /**
     * Deletes rows matching {@code whereClause} from {@code table} in batches of at most
     * {@link #BATCH_SIZE}, looping until a batch deletes fewer than the cap. PostgreSQL's
     * {@code DELETE} statement has no {@code LIMIT} clause (unlike {@code SELECT}), so the batch
     * bound is expressed as a {@code ctid}-restricted subquery — the standard Postgres idiom for
     * "delete at most N matching physical rows in one statement" — rather than appended directly.
     * {@code table} always comes from {@link #discoverAuditTables()} (identifier-validated) or
     * the {@code "revinfo"} literal, never caller/user input.
     */
    @SuppressWarnings("java:S2077") // A table name cannot be a bind parameter; every interpolated
    // name comes from pg_catalog and passes validateAuditTableName's strict identifier allowlist.
    // The cutoff — the only caller-supplied value — is bound, not formatted.
    private int deleteBatched(String table, String whereClause, long cutoffMs) {
        String sql = "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table + " WHERE " + whereClause
                + " LIMIT " + BATCH_SIZE + ")";
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = entityManager
                    .createNativeQuery(sql)
                    .setParameter("cutoff", cutoffMs)
                    .executeUpdate();
            totalDeleted += deleted;
            if (deleted > 0) {
                entityManager.flush();
            }
        } while (deleted == BATCH_SIZE);
        return totalDeleted;
    }
}
