package com.keplerops.groundcontrol.unit.test.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.test.oracle.AbstractPortConformanceSuite;
import com.keplerops.groundcontrol.test.oracle.DifferentialOracle;
import com.keplerops.groundcontrol.test.oracle.GoldenCorpus;
import com.keplerops.groundcontrol.test.oracle.NegativeSuite;
import com.keplerops.groundcontrol.test.oracle.OracleInvariants;
import com.keplerops.groundcontrol.test.oracle.PortImplementation;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class OracleBatteryScaffoldTest extends AbstractPortConformanceSuite<OracleBatteryScaffoldTest.StringPort> {

    interface StringPort {
        String normalize(String value);
    }

    @Override
    protected List<PortImplementation<StringPort>> implementations() {
        return List.of(
                new PortImplementation<>(
                        "trim-lower", () -> value -> value.trim().toLowerCase(Locale.ROOT)),
                new PortImplementation<>(
                        "locale-stable", () -> value -> value.strip().toLowerCase(Locale.ROOT)));
    }

    @TestFactory
    Stream<DynamicTest> conformanceSuiteRunsTheSameBehaviorAgainstEveryImplementation() {
        return conformanceCase("normalizes boundary input", port -> {
            assertThat(port.normalize("  CLD  ")).isEqualTo("cld");
            assertThat(port.normalize("\tOracle\n")).isEqualTo("oracle");
        });
    }

    @Test
    void negativeSuiteBuildsExecutableCasesFromContractData() {
        var cases = List.of(
                NegativeSuite.caseExpecting(
                        "authz:anonymous",
                        NegativeSuite.Kind.AUTHORIZATION,
                        () -> {
                            throw new IllegalStateException("anonymous denied");
                        },
                        IllegalStateException.class),
                NegativeSuite.caseExpecting(
                        "input:blank",
                        NegativeSuite.Kind.INVALID_INPUT,
                        () -> {
                            throw new IllegalArgumentException("blank rejected");
                        },
                        IllegalArgumentException.class));

        assertThat(NegativeSuite.dynamicTests(cases)).hasSize(2);
    }

    @Test
    void negativeSuiteFailsWhenACaseDoesNotReject() {
        var accepted = NegativeSuite.caseExpecting(
                "protocol:illegal-transition",
                NegativeSuite.Kind.PROTOCOL_VIOLATION,
                () -> {},
                IllegalStateException.class);

        assertThatThrownBy(() -> NegativeSuite.assertRejects(accepted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("protocol:illegal-transition");
    }

    @Test
    void conformanceSuiteRejectsEmptyImplementationSet() {
        var suite = new EmptyConformanceSuite();

        assertThatThrownBy(() -> suite.tests().toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one implementation");
    }

    @Test
    void negativeSuiteRejectsEmptyCaseSet() {
        assertThatThrownBy(() -> NegativeSuite.dynamicTests(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one case");
    }

    @Test
    void goldenCorpusRejectsEmptyCaseSet() {
        assertThatThrownBy(() -> new GoldenCorpus<>("empty", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one case");
    }

    @Test
    void invariantHelpersCoverCommonPropertyShapes() {
        UnaryOperator<String> normalize = value -> value.trim().toLowerCase(Locale.ROOT);

        OracleInvariants.assertIdempotent("normalize", "  CLD  ", normalize);
        OracleInvariants.assertRoundTrip("integer-text", 42, Object::toString, Integer::parseInt);
        OracleInvariants.assertOrdered("sorted", List.of(1, 2, 3), Integer::compareTo);
    }

    @Test
    void invariantHelpersFailOnRealPropertyViolations() {
        assertThatThrownBy(() -> OracleInvariants.assertIdempotent("broken-idempotence", "x", value -> value + "!"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("broken-idempotence");
        assertThatThrownBy(() -> OracleInvariants.assertRoundTrip("broken-round-trip", 42, Object::toString, text -> 0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("broken-round-trip");
        assertThatThrownBy(() -> OracleInvariants.assertOrdered("broken-order", List.of(2, 1), Integer::compareTo))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("broken-order");
    }

    @Test
    void differentialOracleFailsWhenReferenceModelAndImplementationDiverge() {
        assertThatThrownBy(() -> DifferentialOracle.assertEquivalent(
                        "text-normalization", " CLD ", value -> value.trim().toLowerCase(Locale.ROOT), value -> value))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("text-normalization");
    }

    @Test
    void goldenCorpusPinsExactOutputsAndCounts() {
        var calls = new AtomicInteger();
        var corpus = new GoldenCorpus<>("renderer", List.of(new GoldenCorpus.Case<>("lowercase", "CLD", "cld")));

        var tests = corpus.dynamicTests(input -> {
            calls.incrementAndGet();
            return input.toLowerCase(Locale.ROOT);
        });

        assertThat(corpus.pinnedCount()).isEqualTo(1);
        assertThat(tests).hasSize(1);
        assertThat(calls).hasValue(0);
    }

    @Test
    void goldenCorpusDynamicTestFailsWhenRendererDriftsFromPinnedOutput() {
        var corpus = new GoldenCorpus<>("renderer", List.of(new GoldenCorpus.Case<>("lowercase", "CLD", "cld")));
        var tests = corpus.dynamicTests(input -> input);

        assertThatThrownBy(() -> tests.get(0).getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("lowercase");
    }

    private static final class EmptyConformanceSuite extends AbstractPortConformanceSuite<StringPort> {

        @Override
        protected List<PortImplementation<StringPort>> implementations() {
            return List.of();
        }

        Stream<DynamicTest> tests() {
            return conformanceCase("normalizes boundary input", port -> assertThat(port.normalize("  CLD  "))
                    .isEqualTo("cld"));
        }
    }
}
