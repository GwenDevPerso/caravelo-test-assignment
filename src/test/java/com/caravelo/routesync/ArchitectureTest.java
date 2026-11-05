package com.caravelo.routesync;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.caravelo.routesync");

    @Test
    @DisplayName("AC17 — no class outside adapter packages depends on an adapter")
    void coreDoesNotDependOnAdapters() {
        noClasses().that().resideOutsideOfPackage("..adapter..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("AC17 — adapters do not depend on each other")
    void adaptersAreIsolated() {
        slices().matching("com.caravelo.routesync.adapter.(*)..")
                .should().notDependOnEachOther()
                .check(CLASSES);
    }

    @Test
    @DisplayName("AC17 — adapters depend only on core among application packages")
    void adaptersDependOnlyOnCore() {
        noClasses().that().resideInAPackage("com.caravelo.routesync.adapter..")
                .should().dependOnClassesThat(resideInAPackage("com.caravelo.routesync..")
                        .and(not(resideInAnyPackage("com.caravelo.routesync.core..", "com.caravelo.routesync.adapter.."))))
                .check(CLASSES);
    }
}
