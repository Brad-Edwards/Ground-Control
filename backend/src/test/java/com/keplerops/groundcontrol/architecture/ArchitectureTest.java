package com.keplerops.groundcontrol.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import jakarta.validation.Valid;
import java.lang.annotation.Annotation;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.keplerops.groundcontrol", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_api = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..api..");

    @ArchTest
    static final ArchRule domain_should_not_depend_on_infrastructure = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule api_should_not_depend_on_infrastructure = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule exceptions_should_extend_base = classes()
            .that()
            .resideInAPackage("..exception..")
            .and()
            .haveNameNotMatching(".*\\.package-info")
            .and()
            .areNotAssignableFrom(com.keplerops.groundcontrol.domain.exception.GroundControlException.class)
            .should()
            .beAssignableTo(com.keplerops.groundcontrol.domain.exception.GroundControlException.class);

    @ArchTest
    static final ArchRule controllers_must_not_access_repositories = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule controllers_must_not_import_domain_entities = noClasses()
            .that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..model..");

    @ArchTest
    static final ArchRule services_must_reside_in_service_package = classes()
            .that()
            .areAnnotatedWith(org.springframework.stereotype.Service.class)
            .should()
            .resideInAPackage("..service..");

    @ArchTest
    static final ArchRule package_cycles_are_frozen = FreezingArchRule.freeze(
            slices().matching("com.keplerops.groundcontrol.(*)..").should().beFreeOfCycles());

    @ArchTest
    static final ArchRule exception_handlers_must_route_through_global_handler = FreezingArchRule.freeze(methods()
            .that()
            .areAnnotatedWith(ExceptionHandler.class)
            .should()
            .beDeclaredIn(com.keplerops.groundcontrol.api.GlobalExceptionHandler.class));

    @ArchTest
    static final ArchRule application_code_must_not_hand_roll_spring_error_envelopes =
            FreezingArchRule.freeze(noClasses()
                    .that()
                    .resideOutsideOfPackage("..api..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.http.ProblemDetail")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.server.ResponseStatusException"));

    @ArchTest
    static final ArchRule logger_factory_get_logger_calls_are_frozen =
            FreezingArchRule.freeze(noClasses().should().callMethod(LoggerFactory.class, "getLogger", Class.class));

    @ArchTest
    static final ArchRule request_bodies_must_be_validated = FreezingArchRule.freeze(methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .should(haveValidOnEveryRequestBodyParameter()));

    @ArchTest
    static final ArchRule mutating_service_methods_must_have_write_transaction = FreezingArchRule.freeze(methods()
            .that()
            .arePublic()
            .and()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Service.class)
            .and()
            .haveNameMatching(
                    "^(add|approve|archive|attach|complete|copy|create|delete|disable|enable|import|install|move|reorder|remove|register|reject|save|set|start|sync|transition|update|upgrade|supersede).*")
            .should(haveWriteTransaction()));

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaMethod> haveValidOnEveryRequestBodyParameter() {
        return new ArchCondition<>("have @Valid on every @RequestBody parameter") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaMethod method, ConditionEvents events) {
                var reflected = method.reflect();
                var parameterAnnotations = reflected.getParameterAnnotations();
                for (var index = 0; index < parameterAnnotations.length; index++) {
                    if (hasAnnotation(parameterAnnotations[index], RequestBody.class)
                            && !hasAnnotation(parameterAnnotations[index], Valid.class)) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                method.getFullName() + " has @RequestBody parameter " + index + " without @Valid"));
                    }
                }
            }
        };
    }

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaMethod> haveWriteTransaction() {
        return new ArchCondition<>("have @Transactional with readOnly=false on the method or service class") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaMethod method, ConditionEvents events) {
                if (!hasWriteTransactionalBoundary(method)) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " mutates service state without a write transaction"));
                }
            }
        };
    }

    private static boolean hasAnnotation(Annotation[] annotations, Class<? extends Annotation> annotationClass) {
        for (var annotation : annotations) {
            if (annotation.annotationType().equals(annotationClass)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWriteTransactionalBoundary(com.tngtech.archunit.core.domain.JavaMethod method) {
        var methodTransaction = method.reflect().getAnnotation(Transactional.class);
        if (methodTransaction != null) {
            return !methodTransaction.readOnly();
        }
        var classTransaction = method.reflect().getDeclaringClass().getAnnotation(Transactional.class);
        return classTransaction != null && !classTransaction.readOnly();
    }
}
