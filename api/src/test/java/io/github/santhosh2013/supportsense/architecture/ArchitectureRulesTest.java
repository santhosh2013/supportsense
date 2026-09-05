package io.github.santhosh2013.supportsense.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture C is only real if it is enforced. These rules run from the first commit —
 * introduced later they would fail on dozens of files at once and get deleted rather than fixed.
 */
class ArchitectureRulesTest {

    private static final String BASE_PACKAGE = "io.github.santhosh2013.supportsense";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    @DisplayName("domain classes have no Spring dependency")
    void domainIsFreeOfSpring() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .because("the pure domain core must be testable with no Spring context")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("domain classes have no persistence dependency")
    void domainIsFreeOfPersistence() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
                .because("domain invariants must not be coupled to the persistence model")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("web layer never touches persistence directly")
    void webDoesNotDependOnPersistence() {
        // Scoped to @Entity classes specifically, not the whole ..persistence.. package —
        // enums (TicketStatus, TicketEventType, ...) are shared vocabulary between layers
        // and are exactly what response DTOs are meant to carry. Only entities must never
        // leak through a controller.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..web..")
                .should()
                .dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate
                                .describe(
                                        "reside in ..persistence.. and are annotated with @Entity",
                                        clazz -> clazz.getPackageName().contains(".persistence")
                                                && clazz.isAnnotatedWith(jakarta.persistence.Entity.class)))
                .because("entities must never be exposed from a controller")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("persistence layer does not depend on web or app")
    void persistenceDoesNotDependUpwards() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..web..", "..app..")
                .because("dependencies point inwards, never back towards the boundary")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("TriageResult and DuplicateLink stay persistence-only in A1")
    void triageAndDuplicateLinkAreNotUsedOutsidePersistence() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("..triage.persistence..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("TriageResult")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("DuplicateLink")
                .because("A1 scopes these entities as persistence-only: no service, "
                        + "no endpoint, no business logic until A2/A5")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("only TicketService reads tickets — BR-A10 visibility must not be bypassed")
    void onlyTicketServiceQueriesTicketRepository() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackages("..ticket.app..", "..ticket.persistence..")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("TicketRepository")
                .because("BR-A10 visibility filtering is composed once in TicketService via "
                        + "TicketSpecifications.visibleTo — any other caller risks bypassing it")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("production code never reads the clock inline")
    void noInlineClockAccess() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("..common.config..")
                .should()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .orShould()
                .callMethod(java.time.LocalDate.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .because("TimeSource must be injected so time-dependent logic is testable");

        rule.check(productionClasses);
    }
}
