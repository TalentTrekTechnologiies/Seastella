package com.seastella.troubleshooting.internal;

import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("guided-check rules (TSA-03)")
class FlowRulesTest {

    private static FlowRules.Branch to(String key) { return new FlowRules.Branch(key, null); }

    private static FlowRules.Branch ends(TroubleshootingOutcome outcome) { return new FlowRules.Branch(null, outcome); }

    private static FlowRules.Step step(String key, FlowRules.Branch yes, FlowRules.Branch no) {
        return new FlowRules.Step(key, "Question " + key + "?", null, yes, no);
    }

    private static final FlowRules.Branch RESOLVED = ends(TroubleshootingOutcome.RESOLVED);
    private static final FlowRules.Branch UNRESOLVED = ends(TroubleshootingOutcome.UNRESOLVED);

    @Nested
    @DisplayName("a draft")
    class Structural {

        @Test
        @DisplayName("needs at least one check")
        void empty() {
            assertThat(FlowRules.structural("s1", List.of())).containsExactly("Add at least one check.");
        }

        @Test
        @DisplayName("needs a first check that exists")
        void start() {
            assertThat(FlowRules.structural("missing", List.of(step("s1", RESOLVED, UNRESOLVED))))
                    .containsExactly("Choose which check comes first.");
        }

        @Test
        @DisplayName("needs a question on every check")
        void prompt() {
            var s = new FlowRules.Step("s1", "  ", null, RESOLVED, UNRESOLVED);
            assertThat(FlowRules.structural("s1", List.of(s))).containsExactly("Check 1 has no question.");
        }

        @Test
        @DisplayName("needs every answer to go to exactly one place")
        void branches() {
            var both = new FlowRules.Branch("s1", TroubleshootingOutcome.RESOLVED);
            var neither = new FlowRules.Branch(null, null);
            assertThat(FlowRules.structural("s1", List.of(step("s1", both, neither))))
                    .containsExactly(
                            "Check 1: say where \"Yes\" leads - another check or an outcome.",
                            "Check 1: say where \"No\" leads - another check or an outcome.");
        }

        @Test
        @DisplayName("refuses answers that lead to a missing check or back to the same one")
        void references() {
            assertThat(FlowRules.structural("s1", List.of(step("s1", to("gone"), to("s1")))))
                    .containsExactly(
                            "Check 1: \"Yes\" leads to a check that no longer exists.",
                            "Check 1: \"No\" leads back to the same check.");
        }

        @Test
        @DisplayName("refuses repeated or malformed keys")
        void keys() {
            assertThat(FlowRules.structural("s1", List.of(
                    step("s1", RESOLVED, UNRESOLVED),
                    step("s1", RESOLVED, UNRESOLVED),
                    step("Bad Key", RESOLVED, UNRESOLVED))))
                    .contains("Check 2 repeats the key of check 1.", "Check 3 has an invalid internal key.");
        }

        @Test
        @DisplayName("is capped in length")
        void cap() {
            List<FlowRules.Step> many = new ArrayList<>();
            for (int i = 0; i <= FlowRules.MAX_STEPS; i++) many.add(step("s" + i, RESOLVED, UNRESOLVED));
            assertThat(FlowRules.structural("s0", many)).anyMatch(p -> p.startsWith("Keep a set of checks to"));
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publishable {

        @Test
        @DisplayName("accepts branching checks where every path ends")
        void valid() {
            assertThat(FlowRules.publishable("power", List.of(
                    step("power", to("restart"), to("fuse")),
                    step("fuse", ends(TroubleshootingOutcome.TEMPORARY_FIX), UNRESOLVED),
                    step("restart", RESOLVED, UNRESOLVED)))).isEmpty();
        }

        @Test
        @DisplayName("accepts a retry loop that has a way out")
        void loopWithExit() {
            assertThat(FlowRules.publishable("a", List.of(
                    step("a", to("b"), RESOLVED),
                    step("b", to("a"), UNRESOLVED)))).isEmpty();
        }

        @Test
        @DisplayName("refuses a check no answer leads to")
        void unreachable() {
            assertThat(FlowRules.publishable("a", List.of(
                    step("a", RESOLVED, UNRESOLVED),
                    step("orphan", RESOLVED, UNRESOLVED))))
                    .containsExactly("Check 2 is never reached: no answer leads to it.");
        }

        @Test
        @DisplayName("refuses a loop with no outcome, naming every check caught in it")
        void trap() {
            assertThat(FlowRules.publishable("a", List.of(
                    step("a", to("b"), to("b")),
                    step("b", to("c"), to("a")),
                    step("c", to("a"), to("b")))))
                    .containsExactly(
                            "From check 1 the answers only go round in a loop; give one of them an outcome.",
                            "From check 2 the answers only go round in a loop; give one of them an outcome.",
                            "From check 3 the answers only go round in a loop; give one of them an outcome.");
        }

        @Test
        @DisplayName("reports structural problems before graph problems")
        void structuralFirst() {
            assertThat(FlowRules.publishable("a", List.of(step("a", to("gone"), UNRESOLVED))))
                    .containsExactly("Check 1: \"Yes\" leads to a check that no longer exists.");
        }
    }
}
