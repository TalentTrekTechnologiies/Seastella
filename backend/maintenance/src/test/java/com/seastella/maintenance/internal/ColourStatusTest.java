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
                new MaintenanceThreshold(null, "URGENT", 1, 9),
                new MaintenanceThreshold(null, "APPROACHING", 10, 15)));
    }

    @Nested
    @DisplayName("published band boundaries")
    class Boundaries {

        @ParameterizedTest(name = "{0} days remaining -> {1}")
        @CsvSource({
                "60, NORMAL",
                "16, NORMAL",     // just above the approaching band
                "15, APPROACHING", // upper edge
                "12, APPROACHING",
                "10, APPROACHING", // lower edge
                "9,  URGENT",      // upper edge
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
         * OI-02: the published bands "10-15" and "1-9" do not meet. No day count
         * may fall through to null, whatever the configuration says.
         */
        @ParameterizedTest
        @ValueSource(ints = {-1000, -1, 0, 1, 9, 10, 15, 16, 1000})
        void neverReturnsNull(int days) {
            assertThat(seededEngine().classify(days, 1L)).isNotNull();
        }

        @Test
        void fallsBackToDefaultsWhenNoThresholdsConfigured() {
            DefaultMaintenanceStatusEngine engine = engineWith(List.of());

            assertThat(engine.classify(20, 1L)).isEqualTo(DueStatus.NORMAL);
            assertThat(engine.classify(12, 1L)).isEqualTo(DueStatus.APPROACHING);
            assertThat(engine.classify(4, 1L)).isEqualTo(DueStatus.URGENT);
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
                    new MaintenanceThreshold(null, "URGENT", 1, 9),
                    new MaintenanceThreshold(null, "APPROACHING", 10, 15)));

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

        /** Due and Overdue are both red, by specification. */
        @Test
        void dueAndOverdueShareTheRedColour() {
            assertThat(DueStatus.DUE.colour()).isEqualTo("red");
            assertThat(DueStatus.OVERDUE.colour()).isEqualTo("red");
        }
    }
}
