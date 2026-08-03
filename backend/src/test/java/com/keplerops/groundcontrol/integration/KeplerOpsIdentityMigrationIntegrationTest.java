package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Exercises V212's KeplerOps -> autarchy-ai traceability identity repair against the actual migration SQL. */
class KeplerOpsIdentityMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void rewritesLegacyKeplerOpsIdentityAndLeavesCanonicalRowsUntouched() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var requirementId = insertRequirement(connection, "MIG-V212-IDENTITY");
                var staleLinkId = insertLink(
                        connection,
                        requirementId,
                        "KeplerOps/Ground-Control#42",
                        "https://github.com/KeplerOps/Ground-Control/issues/42");
                var canonicalLinkId = insertLink(
                        connection,
                        requirementId,
                        "autarchy-ai/Ground-Control#99",
                        "https://github.com/autarchy-ai/Ground-Control/issues/99");

                executeMigration(connection);

                // The stale identity is rewritten in both the identifier and the URL; the issue number is preserved.
                assertThat(readIdentifier(connection, staleLinkId)).isEqualTo("autarchy-ai/Ground-Control#42");
                assertThat(readUrl(connection, staleLinkId))
                        .isEqualTo("https://github.com/autarchy-ai/Ground-Control/issues/42");
                // A row that already names the canonical identity is left byte-for-byte unchanged.
                assertThat(readIdentifier(connection, canonicalLinkId)).isEqualTo("autarchy-ai/Ground-Control#99");
                assertThat(readUrl(connection, canonicalLinkId))
                        .isEqualTo("https://github.com/autarchy-ai/Ground-Control/issues/99");
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

    private static UUID insertLink(Connection connection, UUID requirementId, String identifier, String url)
            throws Exception {
        var linkId = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO traceability_link "
                + "(id, requirement_id, artifact_type, artifact_identifier, artifact_url, link_type, created_at, updated_at) "
                + "VALUES (?, ?, 'GITHUB_ISSUE', ?, ?, 'IMPLEMENTS', now(), now())")) {
            statement.setObject(1, linkId);
            statement.setObject(2, requirementId);
            statement.setString(3, identifier);
            statement.setString(4, url);
            statement.executeUpdate();
        }
        return linkId;
    }

    private static String readIdentifier(Connection connection, UUID linkId) throws Exception {
        try (var statement =
                connection.prepareStatement("SELECT artifact_identifier FROM traceability_link WHERE id = ?")) {
            statement.setObject(1, linkId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String readUrl(Connection connection, UUID linkId) throws Exception {
        try (var statement = connection.prepareStatement("SELECT artifact_url FROM traceability_link WHERE id = ?")) {
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
                        new ClassPathResource("db/migration/V212__normalize_keplerops_repository_identity.sql")),
                false,
                false,
                ScriptUtils.DEFAULT_COMMENT_PREFIX,
                ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
    }
}
