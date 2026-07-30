package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Exercises V211's legacy GitHub traceability repair against the actual migration SQL. */
class GitHubTraceabilityIdentifierMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void normalizesKnownLegacyValuesWithoutRewritingAuditHistory() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var requirementId = insertRequirement(connection, "MIG-V211-NORMALIZE");
                var issueLinkId = insertLink(connection, requirementId, "GITHUB_ISSUE", "#42", "IMPLEMENTS");
                var pullRequestLinkId = insertLink(connection, requirementId, "PULL_REQUEST", "#43", "DOCUMENTS");
                var malformedLinkId = insertLink(connection, requirementId, "GITHUB_ISSUE", "GH-44", "DOCUMENTS");
                var oversizedLinkId = insertLink(connection, requirementId, "GITHUB_ISSUE", "#2147483648", "DOCUMENTS");
                insertAuditSnapshot(connection, issueLinkId, requirementId, "GITHUB_ISSUE", "#42", "IMPLEMENTS");

                executeMigration(connection);

                assertThat(readLiveIdentifier(connection, issueLinkId)).isEqualTo("42");
                assertThat(readLiveIdentifier(connection, pullRequestLinkId)).isEqualTo("43");
                assertThat(readLiveIdentifier(connection, malformedLinkId)).isEqualTo("GH-44");
                assertThat(readLiveIdentifier(connection, oversizedLinkId)).isEqualTo("#2147483648");
                assertThat(readAuditIdentifier(connection, issueLinkId)).isEqualTo("#42");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void refusesToNormalizeWhenCanonicalLinkAlreadyExists() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var requirementId = insertRequirement(connection, "MIG-V211-COLLISION");
                insertLink(connection, requirementId, "GITHUB_ISSUE", "#42", "IMPLEMENTS");
                insertLink(connection, requirementId, "GITHUB_ISSUE", "42", "IMPLEMENTS");

                assertThatThrownBy(() -> executeMigration(connection))
                        .rootCause()
                        .hasMessageContaining("canonical GitHub traceability identifier collision");
            } finally {
                connection.rollback();
            }
        }
    }

    private static UUID insertRequirement(Connection connection, String uid) throws Exception {
        var requirementId = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO requirement "
                + "(id, project_id, uid, title, statement, created_at, updated_at) "
                + "VALUES (?, 'a0000000-0000-0000-0000-000000000001', ?, ?, 'statement', now(), now())")) {
            statement.setObject(1, requirementId);
            statement.setString(2, uid);
            statement.setString(3, uid);
            statement.executeUpdate();
        }
        return requirementId;
    }

    private static UUID insertLink(
            Connection connection, UUID requirementId, String artifactType, String identifier, String linkType)
            throws Exception {
        var linkId = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO traceability_link "
                + "(id, requirement_id, artifact_type, artifact_identifier, link_type, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, now(), now())")) {
            statement.setObject(1, linkId);
            statement.setObject(2, requirementId);
            statement.setString(3, artifactType);
            statement.setString(4, identifier);
            statement.setString(5, linkType);
            statement.executeUpdate();
        }
        return linkId;
    }

    private static void insertAuditSnapshot(
            Connection connection,
            UUID linkId,
            UUID requirementId,
            String artifactType,
            String identifier,
            String linkType)
            throws Exception {
        int revision;
        try (var statement = connection.prepareStatement("INSERT INTO revinfo (revtstmp) VALUES (0) RETURNING rev");
                var result = statement.executeQuery()) {
            result.next();
            revision = result.getInt(1);
        }
        try (var statement = connection.prepareStatement("INSERT INTO traceability_link_audit "
                + "(id, rev, revtype, requirement_id, artifact_type, artifact_identifier, link_type) "
                + "VALUES (?, ?, 0, ?, ?, ?, ?)")) {
            statement.setObject(1, linkId);
            statement.setInt(2, revision);
            statement.setObject(3, requirementId);
            statement.setString(4, artifactType);
            statement.setString(5, identifier);
            statement.setString(6, linkType);
            statement.executeUpdate();
        }
    }

    private static String readLiveIdentifier(Connection connection, UUID linkId) throws Exception {
        try (var statement =
                connection.prepareStatement("SELECT artifact_identifier FROM traceability_link WHERE id = ?")) {
            statement.setObject(1, linkId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String readAuditIdentifier(Connection connection, UUID linkId) throws Exception {
        try (var statement =
                connection.prepareStatement("SELECT artifact_identifier FROM traceability_link_audit WHERE id = ?")) {
            statement.setObject(1, linkId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static void executeMigration(Connection connection) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(
                        new ClassPathResource("db/migration/V211__normalize_github_traceability_identifiers.sql")),
                false,
                false,
                ScriptUtils.DEFAULT_COMMENT_PREFIX,
                ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
    }
}
