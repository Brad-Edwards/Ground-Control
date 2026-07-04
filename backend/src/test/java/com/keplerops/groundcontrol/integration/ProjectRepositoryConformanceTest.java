package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.test.oracle.AbstractPortConformanceSuite;
import com.keplerops.groundcontrol.test.oracle.PortImplementation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * First GC-O014 port conformance suite over an existing repository behavior.
 * The same contract runs against a test-owned in-memory implementation and the
 * real JPA/Postgres repository adapter.
 */
@Transactional
class ProjectRepositoryConformanceTest extends BaseIntegrationTest
        implements AbstractPortConformanceSuite<ProjectRepositoryConformanceTest.ProjectPort> {

    @Autowired
    private ProjectRepository projectRepository;

    @Override
    public List<PortImplementation<ProjectPort>> implementations() {
        return List.of(
                new PortImplementation<>("in-memory", InMemoryProjectPort::new),
                new PortImplementation<>("jpa", () -> new JpaProjectPort(projectRepository)));
    }

    @TestFactory
    Stream<DynamicTest> createdProjectCanBeFoundByIdentifier() {
        return conformanceCase("created project can be found by identifier", port -> {
            String identifier = "contract-" + UUID.randomUUID();

            port.save(identifier, "Contract Project");

            assertThat(port.exists(identifier)).isTrue();
            assertThat(port.find(identifier)).contains(new ProjectSnapshot(identifier, "Contract Project"));
        });
    }

    @TestFactory
    Stream<DynamicTest> unknownProjectIsAbsent() {
        return conformanceCase("unknown project is absent", port -> {
            String identifier = "missing-" + UUID.randomUUID();

            assertThat(port.exists(identifier)).isFalse();
            assertThat(port.find(identifier)).isEmpty();
        });
    }

    interface ProjectPort {
        void save(String identifier, String name);

        Optional<ProjectSnapshot> find(String identifier);

        boolean exists(String identifier);
    }

    record ProjectSnapshot(String identifier, String name) {}

    private static final class InMemoryProjectPort implements ProjectPort {
        private final Map<String, ProjectSnapshot> projects = new HashMap<>();

        @Override
        public void save(String identifier, String name) {
            projects.put(identifier, new ProjectSnapshot(identifier, name));
        }

        @Override
        public Optional<ProjectSnapshot> find(String identifier) {
            return Optional.ofNullable(projects.get(identifier));
        }

        @Override
        public boolean exists(String identifier) {
            return projects.containsKey(identifier);
        }
    }

    private record JpaProjectPort(ProjectRepository repository) implements ProjectPort {
        @Override
        public void save(String identifier, String name) {
            repository.save(new Project(identifier, name));
            repository.flush();
        }

        @Override
        public Optional<ProjectSnapshot> find(String identifier) {
            return repository
                    .findByIdentifier(identifier)
                    .map(project -> new ProjectSnapshot(project.getIdentifier(), project.getName()));
        }

        @Override
        public boolean exists(String identifier) {
            return repository.existsByIdentifier(identifier);
        }
    }
}
