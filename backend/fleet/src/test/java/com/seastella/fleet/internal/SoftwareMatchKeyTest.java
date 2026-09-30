package com.seastella.fleet.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching a vessel's equipment to a row of the client's master sheet.
 *
 * <p>The two sides are typed by different people years apart, so the fold has
 * to be generous. What it must never do is match two different devices: a
 * false match reports a vessel as up to date against another model's release,
 * which is worse than the honest "not known" a miss produces.
 */
@DisplayName("the software baseline match key")
class SoftwareMatchKeyTest {

    @ParameterizedTest(name = "[{0} / {1}] matches [{2} / {3}]")
    @CsvSource({
            "Furuno,  FA-170,  FURUNO,  FA170",
            "Furuno,  FA-170,  furuno,  fa 170",
            "Furuno,  FA-170,  Furuno,  FA_170",
            "JRC,     JAN-9201, J.R.C., JAN 9201",
            "'  Sperry ', VisionMaster, Sperry, VISIONMASTER",
    })
    void foldsHowPeopleVary(String makeA, String modelA, String makeB, String modelB) {
        assertThat(SoftwareMatchKey.of(makeA, modelA))
                .isEqualTo(SoftwareMatchKey.of(makeB, modelB));
    }

    @ParameterizedTest(name = "[{0} / {1}] does not match [{2} / {3}]")
    @CsvSource({
            // One digit apart is a different device with its own releases.
            "Furuno, FA-170, Furuno, FA-171",
            "Furuno, FA-170, Furuno, FA-1700",
            "Furuno, FA-170, JRC,    FA-170",
    })
    void keepsDifferentDevicesApart(String makeA, String modelA, String makeB, String modelB) {
        assertThat(SoftwareMatchKey.of(makeA, modelA))
                .isNotEqualTo(SoftwareMatchKey.of(makeB, modelB));
    }

    @Test
    @DisplayName("equipment without both a make and a model has no key at all")
    void needsBoth() {
        assertThat(SoftwareMatchKey.of(null, "FA-170")).isNull();
        assertThat(SoftwareMatchKey.of("Furuno", null)).isNull();
        assertThat(SoftwareMatchKey.of("", "FA-170")).isNull();
        assertThat(SoftwareMatchKey.of("Furuno", "   ")).isNull();
        assertThat(SoftwareMatchKey.of("-", "FA-170"))
                .as("punctuation alone folds to nothing, and nothing is not a make")
                .isNull();
    }

    @Test
    @DisplayName("two items missing the same field do not collide on an empty key")
    void missingFieldsDoNotCollide() {
        // Both fold to the same empty halves. If the key were "|" they would
        // share a baseline, and a radar would take a GPS's software version.
        assertThat(SoftwareMatchKey.of("", "")).isNull();
        assertThat(SoftwareMatchKey.of(null, null)).isNull();
    }
}
