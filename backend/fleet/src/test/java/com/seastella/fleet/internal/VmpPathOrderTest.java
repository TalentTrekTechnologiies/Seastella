package com.seastella.fleet.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equipment is listed the way the VMP template numbers it. Sorted as text,
 * 10 and 11 land between 1 and 2 - the order a Technical Head reported.
 */
@DisplayName("the VMP equipment order")
class VmpPathOrderTest {

    @Test
    @DisplayName("numbers sort as numbers, each parent before its components")
    void numericOrder() {
        List<String> paths = new ArrayList<>(List.of(
                "2", "10.2", "1", "11", "10", "10.10", "3", "10.1", "13.1.2", "13.1", "13", "9"));
        paths.sort(VmpPathOrder.PATHS);

        assertThat(paths).containsExactly(
                "1", "2", "3", "9", "10", "10.1", "10.2", "10.10", "11", "13", "13.1", "13.1.2");
    }

    @Test
    @DisplayName("a hand-entered part that is not a number goes after the numbered ones")
    void textAfterNumbers() {
        List<String> paths = new ArrayList<>(List.of("A", "2", "1.B", "1.1", "1", "02"));
        paths.sort(VmpPathOrder.PATHS);

        assertThat(paths).containsExactly("1", "1.1", "1.B", "2", "02", "A");
    }
}
