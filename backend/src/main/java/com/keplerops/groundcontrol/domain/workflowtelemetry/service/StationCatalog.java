package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The ADR-090 station catalogue as the backend knows it (issue #1355).
 *
 * <p>Loaded from {@code classpath:measurement/gc-station-catalogue-v2.json}, which the build copies
 * from {@code contracts/measurement/} rather than a committed second copy: a mirrored catalogue can
 * drift, and a validator that disagrees with the contract it enforces is worse than no validator.
 *
 * <p>Its purpose is to keep the yield series honest. Station ids are the grouping key for every
 * process variable in the model, so a typo does not fail — it opens a new station with one attempt
 * in it and quietly removes that attempt from the real station's denominator. Nothing downstream
 * can tell that apart from a station that genuinely ran once.
 *
 * <p>Fail-closed at startup, following {@code MethodologyCatalog}: an absent or malformed catalogue
 * is an {@link IllegalStateException} rather than an empty set that would accept every id.
 */
@Component
public final class StationCatalog {

    static final String DEFAULT_RESOURCE = "measurement/gc-station-catalogue-v2.json";

    /** The catalogue alias kind that names an ADR-036 routing stage (issue #1354). */
    private static final String ADR036_STAGE_ALIAS = "adr036_stage";

    private final Set<String> stationIds;
    private final Set<String> markerIds;

    /**
     * ADR-036 routing stage → canonical station id, built from every station's {@code adr036_stage}
     * aliases (ADR-090 amendment, issue #1354). A durable step observation carries {@code phase =
     * stage_id}; this is where the backend, not the emitter, resolves the catalogue station so the
     * two can never disagree.
     */
    private final Map<String, String> stationByStage;

    /**
     * Every ADR-036 stage the catalogue declares — as a station alias, a lifecycle-marker alias, or a
     * declared non-station stage. A stage in this set that resolves to no station is honest
     * modelling (a marker or non-station inspects nothing); a stage NOT in this set is an undeclared
     * phantom the write path refuses rather than opening a station nobody catalogued.
     */
    private final Set<String> knownStages;

    @Autowired
    public StationCatalog() {
        this(DEFAULT_RESOURCE);
    }

    /** Explicit-resource overload so tests can exercise the fail-closed paths against fixtures. */
    public StationCatalog(String resourcePath) {
        JsonNode root;
        try (var stream = new ClassPathResource(resourcePath).getInputStream()) {
            root = new ObjectMapper().readTree(stream);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not load the station catalogue: " + resourcePath, e);
        }
        this.stationIds = ids(root, "stations", "station_id");
        this.markerIds = ids(root, "lifecycle_markers", "marker_id");
        this.stationByStage = stationByStage(root);
        this.knownStages = knownStages(root, stationByStage);
        if (stationIds.isEmpty()) {
            // An empty set would accept nothing, but an empty *parse* means the shape changed and
            // the catalogue was read as vacuous. Refusing to start is the only honest response.
            throw new IllegalStateException("Station catalogue " + resourcePath + " declares no stations");
        }
    }

    private static Set<String> ids(JsonNode root, String arrayField, String idField) {
        var found = new LinkedHashSet<String>();
        for (var entry : root.path(arrayField)) {
            var id = entry.path(idField).asText(null);
            if (id != null && !id.isBlank()) {
                found.add(id);
            }
        }
        return Set.copyOf(found);
    }

    private static Map<String, String> stationByStage(JsonNode root) {
        var byStage = new LinkedHashMap<String, String>();
        for (var station : root.path("stations")) {
            var stationId = station.path("station_id").asText(null);
            if (stationId == null || stationId.isBlank()) {
                continue;
            }
            for (var alias : station.path("aliases").path(ADR036_STAGE_ALIAS)) {
                var stage = alias.asText(null);
                if (stage != null && !stage.isBlank()) {
                    byStage.put(stage, stationId);
                }
            }
        }
        return Map.copyOf(byStage);
    }

    private static Set<String> knownStages(JsonNode root, Map<String, String> stationByStage) {
        var stages = new LinkedHashSet<>(stationByStage.keySet());
        for (var marker : root.path("lifecycle_markers")) {
            for (var alias : marker.path("aliases").path(ADR036_STAGE_ALIAS)) {
                var stage = alias.asText(null);
                if (stage != null && !stage.isBlank()) {
                    stages.add(stage);
                }
            }
        }
        for (var nonStation : root.path("non_station_stages")) {
            var stage = nonStation.path(ADR036_STAGE_ALIAS).asText(null);
            if (stage != null && !stage.isBlank()) {
                stages.add(stage);
            }
        }
        return Set.copyOf(stages);
    }

    /** Whether the id names a station the catalogue defines — something that inspects and reports. */
    public boolean isStation(String stationId) {
        return stationId != null && stationIds.contains(stationId);
    }

    /** Whether the id names a lifecycle marker, which inspects nothing and can carry no verdict. */
    public boolean isMarker(String stationId) {
        return stationId != null && markerIds.contains(stationId);
    }

    /**
     * Resolve an ADR-036 routing stage to its catalogue station id (issue #1354). Empty means the
     * stage is a declared marker or non-station — it correctly maps to no station — OR the stage is
     * undeclared; callers distinguish those with {@link #isKnownStage(String)}.
     */
    public Optional<String> resolveStationForStage(String stageId) {
        if (stageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(stationByStage.get(stageId));
    }

    /** Whether the catalogue declares this ADR-036 stage at all (station, marker, or non-station). */
    public boolean isKnownStage(String stageId) {
        return stageId != null && knownStages.contains(stageId);
    }

    /** The catalogue's station ids, for error messages that can name the valid set. */
    public Set<String> stationIds() {
        return stationIds;
    }
}
