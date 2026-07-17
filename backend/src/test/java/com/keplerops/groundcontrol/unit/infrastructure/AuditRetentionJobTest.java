package com.keplerops.groundcontrol.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.infrastructure.compliance.AuditRetentionJob;
import com.keplerops.groundcontrol.infrastructure.compliance.AuditRetentionProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for {@link AuditRetentionJob}'s catalog-derived table discovery. The
 * catalog query itself (real {@code pg_catalog} lookup against real Postgres) is exercised
 * end-to-end by {@code AuditRetentionJobIntegrationTest}; the Sonar CI job does not run
 * Testcontainers, so this class keeps the identifier-validation defense-in-depth logic — real
 * production behavior — covered without a database.
 */
@ExtendWith(MockitoExtension.class)
class AuditRetentionJobTest {

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
}
