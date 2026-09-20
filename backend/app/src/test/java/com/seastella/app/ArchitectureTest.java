package com.seastella.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules the architecture actually rests on (NFR-07 to NFR-09, SEC-18).
 *
 * <p>These are the guarantees a reviewer would otherwise have to take on trust:
 * that a module is reached only through its published port, that nothing joins
 * across a module boundary, and that no SQL anywhere is built by gluing a value
 * into a string. Each is checked against the compiled code or the source, not
 * against a convention someone remembered to follow.
 */
@DisplayName("architecture rules")
class ArchitectureTest {

    private static final String ROOT = "com.seastella";

    private static final List<String> MODULES = List.of(
            "core", "identity", "fleet", "maintenance", "servicerequest", "troubleshooting",
            "invoice", "masterdataimport", "activityfeed", "notification", "reporting");

    private static JavaClasses platform() {
        // Jars are included on purpose: every sibling module reaches this
        // classpath as a jar, and they are exactly what is being checked.
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Nested
    @DisplayName("module boundaries")
    class Boundaries {

        /**
         * A module's {@code internal} package is its own. Everything another
         * module may use is in its {@code api} package - which is what makes
         * "microservice-ready" a property of the code rather than an intention.
         */
        @Test
        @DisplayName("no module reaches into another module's internals (NFR-07, NFR-08)")
        void internalsAreNotShared() {
            JavaClasses classes = platform();
            for (String module : MODULES) {
                noClasses()
                        .that().resideOutsideOfPackage(ROOT + "." + module + "..")
                        .should().dependOnClassesThat().resideInAPackage(ROOT + "." + module + ".internal..")
                        .as("nothing outside " + module + " may depend on " + module + ".internal")
                        .because("a module is reached through its api package, never through its tables or services")
                        .check(classes);
            }
        }

        /**
         * The app module wires the platform together and may see everything;
         * nothing else may see the app module.
         */
        @Test
        @DisplayName("no module depends on the application that assembles them (NFR-09)")
        void nothingDependsOnTheApp() {
            noClasses()
                    .that().resideOutsideOfPackage(ROOT + ".app..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + ".app..")
                    .check(platform());
        }
    }

    @Nested
    @DisplayName("SQL")
    class Sql {

        /**
         * Every value reaching the database is a bound parameter (SEC-18).
         *
         * <p>Checked in the source rather than at runtime, because the property
         * worth proving is that there is <em>nowhere</em> a value is glued into
         * a statement — not that the handful of endpoints someone thought to
         * test happen to be safe. A test that only pushed quotes through an
         * input would pass while an unreached query stayed vulnerable.
         */
        @Test
        @DisplayName("no statement is built by concatenating a value (SEC-18)")
        void everyStatementIsParameterised() throws IOException {
            List<String> offences = new ArrayList<>();
            for (Path file : mainSources()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = CONCATENATED_SQL.matcher(source);
                while (m.find()) {
                    offences.add(file.getFileName() + ": " + m.group().replaceAll("\\s+", " ").trim());
                }
            }
            assertThat(offences)
                    .as("SQL built by concatenation; bind a parameter instead")
                    .isEmpty();
        }

        /** A string literal that looks like SQL, joined to something that is not a literal. */
        private static final Pattern CONCATENATED_SQL = Pattern.compile(
                "\"[^\"]*\\b(?i:select|insert\\s+into|update|delete\\s+from|where|order\\s+by|values)\\b[^\"]*\"\\s*\\+\\s*(?!\")[A-Za-z_$]");

        private List<Path> mainSources() throws IOException {
            Path backend = Path.of("").toAbsolutePath().getParent();     // app/ -> backend/
            try (Stream<Path> walk = Files.walk(backend)) {
                return walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                        .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                        .toList();
            }
        }
    }
}
