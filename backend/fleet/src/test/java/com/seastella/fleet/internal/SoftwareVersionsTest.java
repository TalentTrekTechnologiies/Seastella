package com.seastella.fleet.internal;

import com.seastella.fleet.api.SoftwareStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version comparison, against the shapes a fleet's data actually takes.
 *
 * <p>Every case here is a way a hand-typed version string has of defeating
 * the obvious implementation. The one that matters most is 5.10 against 5.9:
 * string comparison calls it older, and reporting an up-to-date ECDIS as
 * three releases behind is how a crew learns to ignore the column.
 */
@DisplayName("comparing an installed software version with its baseline")
class SoftwareVersionsTest {

    @Nested
    @DisplayName("ordinary comparisons")
    class Ordinary {

        @ParameterizedTest(name = "{0} against {1} is OUTDATED")
        @CsvSource({
                "5.5,   5.6",
                "5.9,   5.10",     // string order gets this one backwards
                "4.9.9, 5.0",
                "1,     2",
                "5.6.0, 5.6.1",
        })
        void behindTheBaseline(String installed, String latest) {
            assertThat(SoftwareVersions.compare(installed, latest)).isEqualTo(SoftwareStatus.OUTDATED);
        }

        @ParameterizedTest(name = "{0} against {1} is CURRENT")
        @CsvSource({
                "5.6,    5.6",
                "5.6.0,  5.6",     // a trailing zero is not a different release
                "5.6,    5.6.0",
                "v5.6,   5.6",     // the "v" some About screens print
                "'5.6 ', 5.6",
                "5.06,   5.6",     // a zero-padded minor means the same thing
        })
        void matchesTheBaseline(String installed, String latest) {
            assertThat(SoftwareVersions.compare(installed, latest)).isEqualTo(SoftwareStatus.CURRENT);
        }

        @ParameterizedTest(name = "{0} against {1} is AHEAD")
        @CsvSource({
                "5.7,  5.6",
                "5.10, 5.9",
                "6.0,  5.9.9",
        })
        void aheadOfTheBaseline(String installed, String latest) {
            assertThat(SoftwareVersions.compare(installed, latest)).isEqualTo(SoftwareStatus.AHEAD);
        }
    }

    @Nested
    @DisplayName("when no comparison can honestly be made")
    class Unknown {

        @ParameterizedTest(name = "installed = [{0}]")
        @ValueSource(strings = {"", "   ", "unknown", "N/A", "-", "latest"})
        void installedIsNotAVersion(String installed) {
            assertThat(SoftwareVersions.compare(installed, "5.6")).isEqualTo(SoftwareStatus.UNKNOWN);
        }

        @Test
        @DisplayName("nothing is recorded on the unit")
        void installedIsNull() {
            assertThat(SoftwareVersions.compare(null, "5.6")).isEqualTo(SoftwareStatus.UNKNOWN);
        }

        @Test
        @DisplayName("no baseline covers the model")
        void noBaseline() {
            assertThat(SoftwareVersions.compare("5.6", null)).isEqualTo(SoftwareStatus.UNKNOWN);
            assertThat(SoftwareVersions.compare("5.6", "  ")).isEqualTo(SoftwareStatus.UNKNOWN);
        }

        @Test
        @DisplayName("the numbers agree but a pre-release tail does not, and this class will not rank rc against release")
        void tailDiffers() {
            assertThat(SoftwareVersions.compare("2.1.0-rc3", "2.1.0")).isEqualTo(SoftwareStatus.UNKNOWN);
            assertThat(SoftwareVersions.compare("2.1.0-rc3", "2.1.0-rc4")).isEqualTo(SoftwareStatus.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("the tail is noise, not order")
    class Tails {

        @Test
        @DisplayName("an identical build suffix still reads as current")
        void identicalTails() {
            assertThat(SoftwareVersions.compare("5.6 (build 214)", "5.6 (build 214)"))
                    .isEqualTo(SoftwareStatus.CURRENT);
            assertThat(SoftwareVersions.compare("5.6 (BUILD 214)", "5.6 (build 214)"))
                    .isEqualTo(SoftwareStatus.CURRENT);
        }

        @Test
        @DisplayName("a lower number is outdated whatever the tail says")
        void numbersWinOverTails() {
            assertThat(SoftwareVersions.compare("5.5 (build 999)", "5.6"))
                    .isEqualTo(SoftwareStatus.OUTDATED);
        }
    }
}
