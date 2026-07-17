package com.keplerops.groundcontrol.unit.domain.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionContributor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.envers.Audited;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ADR-084 §5 structural guard: every {@link GraphProjectionContributor} implementation must be
 * backed only by {@code @Audited} JPA entities. A graph snapshot records the Envers revision
 * visible to its projection (see {@code AgeGraphService.materializeGraph}); if a contributor read
 * an unaudited entity, that revision claim would be false for that entity's contribution — an
 * edit could change graph contents without advancing any revision. {@code Document} was the one
 * historical exception (#1309); this test pins the invariant so a future unaudited contributor
 * fails the build instead of silently reopening that hole.
 *
 * <p>Discovery is pure classpath + reflection (no Spring context, no database) so this stays a
 * fast unit test with Sonar coverage: every concrete {@link GraphProjectionContributor} on the
 * classpath is found via {@link org.springframework.core.type.classreading.MetadataReader}-based
 * scanning, and each of its repository-typed fields is resolved to the JPA entity type declared
 * by {@code JpaRepository<Entity, Id>} generics — the same wiring Spring Data itself uses.
 */
class GraphProjectionContributorAuditGuardTest {

    @Test
    void everyContributorIsBackedOnlyByAuditedEntities() {
        List<Class<?>> contributors = discoverContributors();
        // 13 contributors as of #1309; assert a floor (not an exact count) so this test does not
        // need updating every time a new, correctly-audited contributor is added — only when the
        // discovery mechanism itself breaks or a contributor is backed by an unaudited entity.
        assertThat(contributors).hasSizeGreaterThanOrEqualTo(13);

        List<String> violations = new ArrayList<>();
        for (Class<?> contributor : contributors) {
            for (Class<?> entityType : repositoryEntityTypes(contributor)) {
                if (!entityType.isAnnotationPresent(Audited.class)) {
                    violations.add(contributor.getSimpleName() + " -> " + entityType.getSimpleName());
                }
            }
        }

        assertThat(violations)
                .as("every GraphProjectionContributor must read only @Audited entities (ADR-084 §5); "
                        + "an unaudited entity here means the graph's recorded source_revision "
                        + "would not actually reconstruct that entity's contributed state")
                .isEmpty();
    }

    private static List<Class<?>> discoverContributors() {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(GraphProjectionContributor.class));
        var candidates = scanner.findCandidateComponents("com.keplerops.groundcontrol.domain");

        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : candidates) {
            try {
                Class<?> clazz = Class.forName(candidate.getBeanClassName());
                if (!clazz.isInterface() && GraphProjectionContributor.class.isAssignableFrom(clazz)) {
                    classes.add(clazz);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load discovered contributor class", e);
            }
        }
        return classes;
    }

    /** JPA entity types backing every {@code JpaRepository}-typed field declared on {@code contributorClass}. */
    private static Set<Class<?>> repositoryEntityTypes(Class<?> contributorClass) {
        Set<Class<?>> entityTypes = new LinkedHashSet<>();
        for (Field field : contributorClass.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (JpaRepository.class.isAssignableFrom(fieldType)) {
                Class<?> entityType =
                        ResolvableType.forClass(JpaRepository.class, fieldType).resolveGeneric(0);
                if (entityType != null) {
                    entityTypes.add(entityType);
                }
            }
        }
        return entityTypes;
    }
}
