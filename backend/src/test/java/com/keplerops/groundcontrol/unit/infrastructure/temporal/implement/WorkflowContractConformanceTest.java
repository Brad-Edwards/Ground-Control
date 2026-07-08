package com.keplerops.groundcontrol.unit.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementOutcome;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementPhase;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Conformance test for the deterministic {@code /implement} workflow payload contracts (ADR-082 /
 * GC-O014). Reflectively builds a representative instance of every Java record under the contract
 * package, serializes it with Jackson (the shape Temporal history would carry), and validates it
 * against the {@code x-gc-record}-tagged {@code $def} in {@code contracts/schemas/workflow/}: required
 * fields present, no field outside the schema ({@code additionalProperties:false}), and enum values
 * within the schema's closed vocabulary. This is the enforcing test named by every workflow schema's
 * {@code x-ground-control-invariants}.
 */
class WorkflowContractConformanceTest {

    private static final String CONTRACT_PACKAGE =
            "com.keplerops.groundcontrol.infrastructure.temporal.implement.contract";
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void workflowPayloadRecordsConformToSchemas() throws Exception {
        Path schemaDir = schemaDir();
        List<Path> schemaFiles = workflowSchemaFiles(schemaDir);
        assertThat(schemaFiles).as("workflow schema files present").isNotEmpty();

        int recordsChecked = 0;
        for (Path schemaFile : schemaFiles) {
            JsonNode schema = MAPPER.readTree(Files.readString(schemaFile));
            JsonNode defs = schema.get("$defs");
            assertThat(defs).as("%s has $defs", schemaFile.getFileName()).isNotNull();
            for (Map.Entry<String, JsonNode> def : iterable(defs.fields())) {
                JsonNode recordTag = def.getValue().get("x-gc-record");
                assertThat(recordTag)
                        .as("%s#/$defs/%s must declare x-gc-record", schemaFile.getFileName(), def.getKey())
                        .isNotNull();
                Class<?> recordClass = Class.forName(CONTRACT_PACKAGE + "." + recordTag.asText());
                assertThat(recordClass.isRecord())
                        .as("%s is a record", recordClass.getSimpleName())
                        .isTrue();
                JsonNode serialized = MAPPER.valueToTree(sampleInstance(recordClass));
                validate(serialized, def.getValue(), recordClass.getSimpleName());
                recordsChecked++;
            }
        }
        assertThat(recordsChecked)
                .as("every schema $def maps to a record instance")
                .isGreaterThanOrEqualTo(40);
    }

    @Test
    void implementPhaseAndOutcomeEnumsMatchSchema() throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(schemaDir().resolve("implement-workflow.v1.schema.json")));
        JsonNode result = schema.at("/$defs/ImplementWorkflowResult/properties");

        assertThat(enumValues(result.at("/terminalPhase/enum")))
                .containsExactlyInAnyOrder(names(ImplementPhase.values()));
        assertThat(enumValues(result.at("/outcome/enum"))).containsExactlyInAnyOrder(names(ImplementOutcome.values()));
    }

    /**
     * Every constant of every contract enum must match its schema field's closed vocabulary exactly
     * (not just the first constant the record-conformance sampler reaches), so a schema edit that
     * silently drops a non-first enum value is caught — the conformance surface's stated purpose.
     */
    @Test
    void everyContractEnumMatchesItsSchemaVocabulary() throws IOException {
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiState.class,
                "ci-observation.v1.schema.json",
                "/$defs/CiObservationResult/properties/state/enum");
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarStatus.class,
                "sonar-gate.v1.schema.json",
                "/$defs/SonarGateResult/properties/status/enum");
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.PrState.class,
                "merge-observation.v1.schema.json",
                "/$defs/MergeObservationResult/properties/prState/enum");
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewVerdict.class,
                "content-activities.v1.schema.json",
                "/$defs/CodexReviewResult/properties/verdict/enum");
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewerKind.class,
                "implement-signals.v1.schema.json",
                "/$defs/ReviewCapDispositionSignal/properties/reviewer/enum");
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CapDisposition.class,
                "implement-signals.v1.schema.json",
                "/$defs/ReviewCapDispositionSignal/properties/disposition/enum");
        assertEnumMatchesSchema(
                com.keplerops.groundcontrol.domain.requirements.state.Status.class,
                "status-transition.v1.schema.json",
                "/$defs/StatusTransitionInput/properties/targetStatus/enum");
    }

    private static void assertEnumMatchesSchema(Class<?> enumClass, String schemaFile, String enumPointer)
            throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(schemaDir().resolve(schemaFile)));
        List<String> schemaValues = enumValues(schema.at(enumPointer));
        List<String> javaValues = Arrays.stream(enumClass.getEnumConstants())
                .map(constant -> MAPPER.valueToTree(constant).asText())
                .toList();
        assertThat(schemaValues)
                .as("%s vocabulary in %s", enumClass.getSimpleName(), schemaFile)
                .containsExactlyInAnyOrderElementsOf(javaValues);
    }

    private static void validate(JsonNode instance, JsonNode def, String ctx) {
        assertThat(instance.isObject()).as("%s serializes to an object", ctx).isTrue();
        JsonNode properties = def.get("properties");
        JsonNode required = def.get("required");
        if (required != null) {
            for (JsonNode field : required) {
                assertThat(instance.has(field.asText()))
                        .as("%s required field %s present", ctx, field.asText())
                        .isTrue();
            }
        }
        for (Map.Entry<String, JsonNode> field : iterable(instance.fields())) {
            assertThat(properties != null && properties.has(field.getKey()))
                    .as("%s field %s is a declared schema property (additionalProperties:false)", ctx, field.getKey())
                    .isTrue();
            JsonNode propSchema = properties.get(field.getKey());
            if (propSchema.has("enum")) {
                assertThat(enumValues(propSchema.get("enum")))
                        .as("%s field %s enum value", ctx, field.getKey())
                        .contains(field.getValue().asText());
            }
        }
    }

    private static Object sampleInstance(Class<?> recordClass) throws ReflectiveOperationException {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] types =
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] args =
                Arrays.stream(components).map(c -> sampleValue(c.getType())).toArray();
        Constructor<?> ctor = recordClass.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static Object sampleValue(Class<?> type) {
        if (type == String.class) {
            return "sample";
        }
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == long.class || type == Long.class) {
            return 1L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == List.class) {
            return List.of("sample");
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.isRecord()) {
            try {
                return sampleInstance(type);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot build nested contract record " + type, e);
            }
        }
        throw new IllegalStateException("unhandled contract field type: " + type);
    }

    private static Path schemaDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("contracts/schemas/workflow"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root with contracts/schemas/workflow").isNotNull();
        return dir.resolve("contracts/schemas/workflow");
    }

    private static List<Path> workflowSchemaFiles(Path schemaDir) throws IOException {
        try (Stream<Path> stream = Files.list(schemaDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".schema.json"))
                    // workflow-run-record is the ADR-061 telemetry projection, not an activity payload.
                    .filter(p -> !p.getFileName().toString().startsWith("workflow-run-record"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> enumValues(JsonNode enumNode) {
        List<String> values = new ArrayList<>();
        enumNode.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static String[] names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toArray(String[]::new);
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        List<T> list = new ArrayList<>();
        it.forEachRemaining(list::add);
        return list;
    }
}
