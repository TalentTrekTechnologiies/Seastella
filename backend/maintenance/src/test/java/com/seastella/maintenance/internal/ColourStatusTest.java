package com.seastella.maintenance.internal;

import com.seastella.fleet.api.FleetDirectory;
import com.seastella.maintenance.api.DueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MNT-04, MNT-06. The colour bands are the most widely-consumed rule in the
 * platform, so the boundaries are pinned explicitly rather than sampled.
 */
class ColourStatusTest {

    private DefaultMaintenanceStatusEngine engineWith(List<MaintenanceThreshold> rows) {
        MaintenanceThresholdRepository thresholds = mock(MaintenanceThresholdRepository.class);
        when(thresholds.findApplicable(any())).thenReturn(rows);
        return new DefaultMaintenanceStatusEngine(
                mock(SpareMaintenanceRuleRepository.class),
                thresholds,
                mock(FleetDirectory.class));
    }

    private DefaultMaintenanceStatusEngine seededEngine() {
        return engineWith(List.of(
                new MaintenanceThreshold(null, "URGENT", 1, 15),
                new MaintenanceThreshold(null, "APPROACHING", 16, 60)));
    }

    @Nested
    @DisplayName("published band boundaries")
    class Boundaries {

        @ParameterizedTest(name = "{0} days remaining -> {1}")
        @CsvSource({
                "61, NORMAL",
                "70, NORMAL",
                "60, APPROACHING", // upper edge
                "40, APPROACHING",
                "16, APPROACHING", // lower edge
                "15, URGENT",      // upper edge - the client's red notice
                "5,  URGENT",
                "1,  URGENT",      // lower edge
                "0,  DUE",         // due today
                "-1, OVERDUE",
                "-45, OVERDUE"
        })
        void classifiesEachBand(int days, DueStatus expected) {
            assertThat(seededEngine().classify(days, 1L)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("the engine is total")
    class Totality {

        /**
         * OI-02: the published bands "10-15" and "1-9" did not meet, and a future
         * configuration may gap the same way. No day count may fall through to
         * null, whatever the rows say.
         */
        @ParameterizedTest
        @ValueSource(ints = {-1000, -1, 0, 1, 15, 16, 60, 61, 1000})
        void neverReturnsNull(int days) {
            assertThat(seededEngine().classify(days, 1L)).isNotNull();
        }

        @Test
        void fallsBackToDefaultsWhenNoThresholdsConfigured() {
            DefaultMaintenanceStatusEngine engine = engineWith(List.of());

            assertThat(engine.classify(70, 1L)).isEqualTo(DueStatus.NORMAL);
            assertThat(engine.classify(40, 1L)).isEqualTo(DueStatus.APPROACHING);
            assertThat(engine.classify(10, 1L)).isEqualTo(DueStatus.URGENT);
            assertThat(engine.classify(0, 1L)).isEqualTo(DueStatus.DUE);
            assertThat(engine.classify(-3, 1L)).isEqualTo(DueStatus.OVERDUE);
        }
    }

    @Nested
    @DisplayName("thresholds are configurable")
    class Configurable {

        /** MNT-05: an organization row must override the platform default. */
        @Test
        void organizationRowOverridesPlatformDefault() {
            DefaultMaintenanceStatusEngine engine = engineWith(List.of(
                    // org-specific: urgent stretches to 20 days
                    new MaintenanceThreshold(7L, "URGENT", 1, 20),
                    new MaintenanceThreshold(null, "URGENT", 1, 15),
                    new MaintenanceThreshold(null, "APPROACHING", 16, 60)));

            assertThat(engine.classify(18, 7L)).isEqualTo(DueStatus.URGENT);
        }
    }

    @Nested
    @DisplayName("status semantics")
    class Semantics {

        @Test
        void attentionAndCriticalGroupingsAreCorrect() {
            assertThat(DueStatus.NORMAL.needsAttention()).isFalse();
            assertThat(DueStatus.APPROACHING.needsAttention()).isTrue();
            assertThat(DueStatus.URGENT.needsAttention()).isTrue();
            assertThat(DueStatus.DUE.needsAttention()).isTrue();
            assertThat(DueStatus.OVERDUE.needsAttention()).isTrue();
            assertThat(DueStatus.NOT_TRACKED.needsAttention()).isFalse();

            assertThat(DueStatus.DUE.isCritical()).isTrue();
            assertThat(DueStatus.OVERDUE.isCritical()).isTrue();
            assertThat(DueStatus.URGENT.isCritical()).isFalse();
        }

        /**
         * Colour alone fails greyscale printing and colour-vision deficiency,
         * and these reports reach class surveyors on paper.
         */
        @Test
        void everyStatusCarriesADistinctShape() {
            assertThat(DueStatus.NORMAL.shape()).isEqualTo("dot");
            assertThat(DueStatus.APPROACHING.shape()).isEqualTo("half-dot");
            assertThat(DueStatus.URGENT.shape()).isEqualTo("triangle");
            assertThat(DueStatus.DUE.shape()).isEqualTo("square");
        }

        /**
         * Due and Overdue are red by specification, and Urgent joined them when
         * the client asked for a red notice at 15 days (V36). Three bands, one
         * colour, told apart by label and shape.
         */
        @Test
        void theRedBandsShareTheRedColour() {
            assertThat(DueStatus.URGENT.colour()).isEqualTo("red");
            assertThat(DueStatus.DUE.colour()).isEqualTo("red");
            assertThat(DueStatus.OVERDUE.colour()).isEqualTo("red");
        }
    }
}
