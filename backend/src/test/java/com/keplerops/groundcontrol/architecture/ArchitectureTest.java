package com.keplerops.groundcontrol.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
