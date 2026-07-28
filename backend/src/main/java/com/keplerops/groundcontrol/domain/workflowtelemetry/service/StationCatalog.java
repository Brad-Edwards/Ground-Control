package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashSet;
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

    private final Set<String> stationIds;
    private final Set<String> markerIds;

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

    /** Whether the id names a station the catalogue defines — something that inspects and reports. */
    public boolean isStation(String stationId) {
        return stationId != null && stationIds.contains(stationId);
    }

    /** Whether the id names a lifecycle marker, which inspects nothing and can carry no verdict. */
    public boolean isMarker(String stationId) {
        return stationId != null && markerIds.contains(stationId);
    }

    /** The catalogue's station ids, for error messages that can name the valid set. */
    public Set<String> stationIds() {
        return stationIds;
    }
}
