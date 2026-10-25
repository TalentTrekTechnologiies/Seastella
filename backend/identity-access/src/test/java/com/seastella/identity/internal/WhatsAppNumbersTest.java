package com.seastella.identity.internal;

import com.seastella.core.api.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("a WhatsApp number as people type it")
class WhatsAppNumbersTest {

    @ParameterizedTest(name = "[{0}] is {1}")
    @CsvSource(delimiter = '|', value = {
            "+91 98765 43210    | +919876543210",
            "+91-98765-43210    | +919876543210",
            "0091 9876543210    | +919876543210",
            "98765 43210        | +919876543210",
            "(+65) 8123 4567    | +6581234567",
            "+44 7700 900123    | +447700900123",
    })
    void folds(String typed, String stored) {
        assertThat(WhatsAppNumbers.normalize(typed)).isEqualTo(stored);
    }

    @ParameterizedTest(name = "[{0}] is refused")
    @ValueSource(strings = {"12345", "5876543210", "+0 98765 43210", "call me", "+91 98765 43210 ext 2"})
    void refuses(String typed) {
        assertThatThrownBy(() -> WhatsAppNumbers.normalize(typed))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("country code");
    }

    @Test
    @DisplayName("blank means no number")
    void blank() {
        assertThat(WhatsAppNumbers.normalize("  ")).isNull();
        assertThat(WhatsAppNumbers.normalize(null)).isNull();
    }
}
