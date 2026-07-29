package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Exercises V210's live/audit backfill against the actual migration SQL. */
class CanonicalStationBindingMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void backfillsOnlyResolvableStationsAndNeverInventsLegacyVerdicts() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var runId = UUID.randomUUID();
                var stationEventId = UUID.randomUUID();
                var markerEventId = UUID.randomUUID();
                var stepEventId = UUID.randomUUID();
                int revision;

                try (var statement = connection.prepareStatement("INSERT INTO workflow_run "
                        + "(id, project, workflow_type, final_state, outcome, provenance) "
                        + "VALUES (?, 'migration-v210', 'IMPLEMENT', 'RUNNING', 'NONE', 'LIVE_EMISSION')")) {
                    statement.setObject(1, runId);
                    statement.executeUpdate();
                }
                insertLiveEvent(connection, stationEventId, runId, "preflight", "station", "ADR061_WORKFLOW_TELEMETRY");
                insertLiveEvent(connection, markerEventId, runId, "plan", "marker", "ADR061_WORKFLOW_TELEMETRY");
                insertLiveEvent(connection, stepEventId, runId, "ci", "step", "ADR036_STEP_JSONL");

                try (var statement =
                                connection.prepareStatement("INSERT INTO revinfo (revtstmp) VALUES (0) RETURNING rev");
                        var result = statement.executeQuery()) {
                    result.next();
                    revision = result.getInt(1);
                }
                insertAuditEvent(
                        connection,
                        stationEventId,
                        runId,
                        revision,
                        "preflight",
                        "station",
                        "ADR061_WORKFLOW_TELEMETRY");
                insertAuditEvent(
                        connection, markerEventId, runId, revision, "plan", "marker", "ADR061_WORKFLOW_TELEMETRY");
                insertAuditEvent(connection, stepEventId, runId, revision, "ci", "step", "ADR036_STEP_JSONL");

                ScriptUtils.executeSqlScript(
                        connection, new ClassPathResource("db/migration/V210__canonical_workflow_station_binding.sql"));

                assertThat(readValue(connection, "workflow_phase_event", stationEventId, "station_id"))
                        .isEqualTo("architecture_preflight");
                assertThat(readValue(connection, "workflow_phase_event_audit", stationEventId, "station_id"))
                        .isEqualTo("architecture_preflight");
                assertThat(readValue(connection, "workflow_phase_event", markerEventId, "station_id"))
                        .isNull();
                assertThat(readValue(connection, "workflow_phase_event_audit", markerEventId, "station_id"))
                        .isNull();
                assertThat(readValue(connection, "workflow_phase_event", stepEventId, "station_id"))
                        .isNull();
                assertThat(readValue(connection, "workflow_phase_event_audit", stepEventId, "station_id"))
                        .isNull();
                assertThat(readValue(connection, "workflow_phase_event", stationEventId, "station_result"))
                        .isEqualTo("UNOBSERVED");
                assertThat(readValue(connection, "workflow_phase_event_audit", stationEventId, "station_result"))
                        .isEqualTo("UNOBSERVED");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void insertLiveEvent(
            Connection connection, UUID eventId, UUID runId, String phase, String sourceSuffix, String emitter)
            throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO workflow_phase_event "
                + "(id, run_id, project, phase, event_type, occurred_at, provenance, source_id, "
                + "station_result, findings_dropped, emitter) "
                + "VALUES (?, ?, 'migration-v210', ?, 'COMPLETED', now(), 'LIVE_EMISSION', ?, "
                + "'UNOBSERVED', 0, ?)")) {
            statement.setObject(1, eventId);
            statement.setObject(2, runId);
            statement.setString(3, phase);
            statement.setString(4, "migration-v210:" + sourceSuffix);
            statement.setString(5, emitter);
            statement.executeUpdate();
        }
    }

    private static void insertAuditEvent(
            Connection connection,
            UUID eventId,
            UUID runId,
            int revision,
            String phase,
            String sourceSuffix,
            String emitter)
            throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO workflow_phase_event_audit "
                + "(id, rev, revtype, run_id, project, phase, event_type, occurred_at, provenance, "
                + "source_id, station_result, findings_dropped, emitter) "
                + "VALUES (?, ?, 0, ?, 'migration-v210', ?, 'COMPLETED', now(), 'LIVE_EMISSION', ?, "
                + "'UNOBSERVED', 0, ?)")) {
            statement.setObject(1, eventId);
            statement.setInt(2, revision);
            statement.setObject(3, runId);
            statement.setString(4, phase);
            statement.setString(5, "migration-v210:" + sourceSuffix);
            statement.setString(6, emitter);
            statement.executeUpdate();
        }
    }

    private static String readValue(Connection connection, String table, UUID eventId, String column) throws Exception {
        var sql = "SELECT " + column + " FROM " + table + " WHERE id = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
