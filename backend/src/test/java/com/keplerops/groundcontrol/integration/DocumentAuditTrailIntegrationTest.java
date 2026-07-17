package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.documents.model.Document;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * Behavioral proof for ADR-084 §5 / #1309: {@code Document} was made {@code @Audited} because it
 * is projected into the AGE graph, and {@code age_graph_snapshot.source_revision} claims to
 * reconstruct exactly the entity state visible at that revision. {@code
 * GraphProjectionContributorAuditGuardTest} only checks the class carries the {@code @Audited}
 * annotation — an existence check, not proof Envers actually persists a revision row. This test
 * commits a real save and a real update and reads them back through {@link AuditReaderFactory},
 * following the same commit-then-verify idiom as {@code TraceabilityLinkIntegrationTest
 * .enversAuditTrailRecordsRevisions}. It fails if {@code @Audited} is removed from {@code Document}
 * ({@code getRevisions} throws {@code NotAuditedException}) and it fails if {@code document_audit}'s
 * column mapping is broken (the commit itself throws at flush time).
 */
@Transactional
class DocumentAuditTrailIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    @Test
    void saveAndUpdateProduceEnversRevisions() {
        Document document = null;
        try {
            // Envers writes audit data on commit, so each step must commit for a revision to appear.
            document = new Document(
                    testProject, "Audit Trail Spec " + UUID.randomUUID(), "1.0", "Initial description", "tester");
            documentRepository.save(document);
            TestTransaction.flagForCommit();
            TestTransaction.end();

            TestTransaction.start();
            var saved = documentRepository.findById(document.getId()).orElseThrow();
            saved.setDescription("Updated description");
            documentRepository.save(saved);
            TestTransaction.flagForCommit();
            TestTransaction.end();

            TestTransaction.start();
            var revisions = AuditReaderFactory.get(entityManager).getRevisions(Document.class, document.getId());
            assertThat(revisions).hasSize(2);
        } finally {
            if (document != null) {
                jdbcTemplate.update("DELETE FROM document WHERE id = ?", document.getId());
            }
            TestTransaction.flagForCommit();
            TestTransaction.end();
            TestTransaction.start();
        }
    }
}
