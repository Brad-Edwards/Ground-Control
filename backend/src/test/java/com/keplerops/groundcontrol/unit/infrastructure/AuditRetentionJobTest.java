package com.keplerops.groundcontrol.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.infrastructure.compliance.AuditRetentionJob;
import com.keplerops.groundcontrol.infrastructure.compliance.AuditRetentionProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for {@link AuditRetentionJob}. The catalog query and DELETE statements
 * themselves (real {@code pg_catalog} lookup and real row deletes against real Postgres) are
 * exercised end-to-end by {@code AuditRetentionJobIntegrationTest}; the Sonar CI job does not run
 * Testcontainers, so this class drives the same production logic through a mocked {@link
 * EntityManager} / {@link Query} so the behavior is covered without a database.
 */
@ExtendWith(MockitoExtension.class)
class AuditRetentionJobTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query catalogQuery;

    private AuditRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new AuditRetentionJob(new AuditRetentionProperties(30, "0 0 3 * * *"), entityManager);
    }

    @SuppressWarnings("unchecked")
    private void stubCatalogResult(List<String> names) {
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("pg_constraint")))
                .thenReturn(catalogQuery);
        when(catalogQuery.getResultList()).thenReturn((List) names);
    }

    /**
     * Every DELETE statement {@code deleteBatched} issues is {@code DELETE FROM <table> WHERE
     * ctid IN (...)}, so matching on that prefix per table gives the catalog query and each
     * table's DELETE query distinct mocks without coupling the test to the full SQL text.
     */
    private Query stubDelete(String table) {
        Query deleteQuery = mock(Query.class);
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.argThat(
                        sql -> sql != null && sql.startsWith("DELETE FROM " + table + " "))))
                .thenReturn(deleteQuery);
        when(deleteQuery.setParameter(eq("cutoff"), anyLong())).thenReturn(deleteQuery);
        return deleteQuery;
    }

    @Test
    void discoverAuditTables_returnsNamesFromCatalog() {
        stubCatalogResult(List.of("requirement_audit", "document_audit", "control_audit"));

        List<String> tables = job.discoverAuditTables();

        assertThat(tables).containsExactly("requirement_audit", "document_audit", "control_audit");
    }

    @Test
    void discoverAuditTables_rejectsNameWithInvalidCharacters() {
        // Defense in depth (mirrors the AGE graph-name allowlist instinct): catalog names come
        // from pg_catalog, never user input, but are still identifier-validated before being
        // interpolated into a DELETE FROM <table> statement.
        stubCatalogResult(List.of("requirement_audit", "evil; DROP TABLE revinfo; --"));

        assertThatThrownBy(() -> job.discoverAuditTables()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void discoverAuditTables_rejectsBlankName() {
        stubCatalogResult(java.util.Collections.singletonList(""));

        assertThatThrownBy(() -> job.discoverAuditTables()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void discoverAuditTables_returnsEmptyWhenCatalogHasNoAuditTables() {
        stubCatalogResult(List.of());

        assertThat(job.discoverAuditTables()).isEmpty();
    }

    @Test
    void validateAuditTableName_rejectsNullName() throws Exception {
        // discoverAuditTables can never feed a real null in here (String.valueOf(null) yields the
        // 4-char string "null", not a null reference), so this arm is unreachable through the
        // public API. It is still real defense-in-depth for any future caller of the private
        // helper that skips the String.valueOf conversion, so invoke it directly via reflection.
        Method validate = AuditRetentionJob.class.getDeclaredMethod("validateAuditTableName", String.class);
        validate.setAccessible(true);

        assertThatThrownBy(() -> invokeStatic(validate, (Object) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid audit table name from catalog: null");
    }

    private static Object invokeStatic(Method method, Object arg) throws Throwable {
        try {
            return method.invoke(null, arg);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void purgeOldAuditRecords_deletesAuditTablesBeforeRevinfo() {
        // Every audit table's rev column is NOT NULL REFERENCES revinfo(rev); deleting revinfo
        // first would raise a foreign-key violation in production. This ordering is the
        // highest-value invariant in the class.
        stubCatalogResult(List.of("document_audit", "requirement_audit"));
        Query documentQuery = stubDelete("document_audit");
        Query requirementQuery = stubDelete("requirement_audit");
        Query revinfoQuery = stubDelete("revinfo");
        when(documentQuery.executeUpdate()).thenReturn(3);
        when(requirementQuery.executeUpdate()).thenReturn(5);
        when(revinfoQuery.executeUpdate()).thenReturn(2);

        job.purgeOldAuditRecords();

        InOrder inOrder = inOrder(documentQuery, requirementQuery, revinfoQuery);
        inOrder.verify(documentQuery).executeUpdate();
        inOrder.verify(requirementQuery).executeUpdate();
        inOrder.verify(revinfoQuery).executeUpdate();
    }

    @Test
    void purgeOldAuditRecords_reissuesDeleteUntilBatchSmallerThanCap() {
        // BATCH_SIZE is 1000: a batch that deletes exactly 1000 rows means more may remain, so the
        // loop must re-issue the DELETE; a smaller batch means the table is exhausted and the loop
        // must stop. This exercises both branches of that condition.
        stubCatalogResult(List.of());
        Query revinfoQuery = stubDelete("revinfo");
        when(revinfoQuery.executeUpdate()).thenReturn(1000, 240);

        job.purgeOldAuditRecords();

        verify(revinfoQuery, times(2)).executeUpdate();
    }

    @Test
    void purgeOldAuditRecords_flushesAfterBatchThatDeletesRows() {
        stubCatalogResult(List.of());
        Query revinfoQuery = stubDelete("revinfo");
        when(revinfoQuery.executeUpdate()).thenReturn(5);

        job.purgeOldAuditRecords();

        verify(entityManager).flush();
    }

    @Test
    void purgeOldAuditRecords_skipsFlushWhenBatchDeletesNothing() {
        stubCatalogResult(List.of());
        Query revinfoQuery = stubDelete("revinfo");
        when(revinfoQuery.executeUpdate()).thenReturn(0);

        job.purgeOldAuditRecords();

        verify(entityManager, never()).flush();
    }

    @Test
    void purgeOldAuditRecords_issuesOnlyRevinfoDeleteWhenCatalogIsEmpty() {
        stubCatalogResult(List.of());
        Query revinfoQuery = stubDelete("revinfo");
        when(revinfoQuery.executeUpdate()).thenReturn(0);

        job.purgeOldAuditRecords();

        // One call for the catalog query, one for the revinfo DELETE, and nothing else — the
        // empty-catalog loop body never runs.
        verify(entityManager, times(2)).createNativeQuery(anyString());
    }

    @Test
    void purgeOldAuditRecords_bindsCutoffDerivedFromRetentionDaysNotFormattedIntoSql() {
        stubCatalogResult(List.of());
        Query revinfoQuery = stubDelete("revinfo");
        when(revinfoQuery.executeUpdate()).thenReturn(0);

        long before = System.currentTimeMillis();
        job.purgeOldAuditRecords();
        long after = System.currentTimeMillis();

        // retentionDays=30 (see setUp): the bound cutoff must be ~30 days before "now", bracketed
        // by timestamps taken immediately before/after the call to absorb System.currentTimeMillis()
        // jitter without asserting an exact, flaky instant.
        ArgumentCaptor<Long> cutoffCaptor = ArgumentCaptor.forClass(Long.class);
        verify(revinfoQuery).setParameter(eq("cutoff"), cutoffCaptor.capture());
        long retentionMs = 30L * DAY_MS;
        assertThat(cutoffCaptor.getValue()).isBetween(before - retentionMs, after - retentionMs);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(2)).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues())
                .as("cutoff must be bound as a query parameter, never formatted into the SQL text")
                .anySatisfy(sql -> assertThat(sql).contains(":cutoff"));
    }
}
