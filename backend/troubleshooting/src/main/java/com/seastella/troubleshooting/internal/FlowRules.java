package com.seastella.troubleshooting.internal;

import com.seastella.troubleshooting.api.TroubleshootingOutcome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What makes a set of guided checks safe to put in front of a Captain.
 *
 * <p>Two levels. {@link #structural} problems make a draft meaningless - an
 * answer that goes nowhere, a check without a question - so a draft cannot be
 * saved with them. {@link #publishable} adds the rules about the whole flow:
 * every check can be reached, and from every check the answers lead to an
 * outcome, so no Captain is ever stuck going round a loop. Messages name checks
 * by their position ("check 3"), which is how the author sees them.
 */
final class FlowRules {

    static final int MAX_STEPS = 60;
    static final int MAX_PROMPT = 500;
    static final int MAX_HELP = 1000;
    private static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,40}$");

    private FlowRules() {
    }

    /** Where one answer goes: the next check, or an outcome that ends the checks. Exactly one. */
    record Branch(String nextKey, TroubleshootingOutcome outcome) {
        boolean ends() { return outcome != null; }
    }

    record Step(String key, String prompt, String helpText, Branch yes, Branch no) {}

    static List<String> structural(String startKey, List<Step> steps) {
        List<String> problems = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            problems.add("Add at least one check.");
            return problems;
        }
        if (steps.size() > MAX_STEPS) {
            problems.add("Keep a set of checks to " + MAX_STEPS + " or fewer; split very long ones by problem type.");
        }

        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String key = s == null ? null : s.key();
            if (key == null || !KEY.matcher(key).matches()) {
                problems.add("Check " + (i + 1) + " has an invalid internal key.");
            } else if (position.putIfAbsent(key, i + 1) != null) {
                problems.add("Check " + (i + 1) + " repeats the key of check " + position.get(key) + ".");
            }
        }
        if (startKey == null || !position.containsKey(startKey)) {
            problems.add("Choose which check comes first.");
        }

        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s == null) continue;
            String n = "Check " + (i + 1);
            if (s.prompt() == null || s.prompt().isBlank()) {
                problems.add(n + " has no question.");
            } else if (s.prompt().trim().length() > MAX_PROMPT) {
                problems.add(n + ": keep the question under " + MAX_PROMPT + " characters.");
            }
            if (s.helpText() != null && s.helpText().trim().length() > MAX_HELP) {
                problems.add(n + ": keep the guidance under " + MAX_HELP + " characters.");
            }
            branch(problems, n, "Yes", s.key(), s.yes(), position);
            branch(problems, n, "No", s.key(), s.no(), position);
        }
        return problems;
    }

    static List<String> publishable(String startKey, List<Step> steps) {
        List<String> problems = structural(startKey, steps);
        if (!problems.isEmpty()) return problems;

        Map<String, Step> byKey = new LinkedHashMap<>();
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            byKey.put(steps.get(i).key(), steps.get(i));
            position.put(steps.get(i).key(), i + 1);
        }

        // Every check can be reached from the first.
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(startKey));
        while (!queue.isEmpty()) {
            String key = queue.pop();
            if (!reached.add(key)) continue;
            Step s = byKey.get(key);
            for (Branch b : List.of(s.yes(), s.no())) {
                if (!b.ends()) queue.push(b.nextKey());
            }
        }
        for (Step s : steps) {
            if (!reached.contains(s.key())) {
                problems.add("Check " + position.get(s.key()) + " is never reached: no answer leads to it.");
            }
        }

        // From every reachable check, some sequence of answers ends the checks.
        // Work backwards from the checks that can end directly.
        Set<String> canFinish = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Step s : steps) {
                if (canFinish.contains(s.key())) continue;
                if (finishes(s.yes(), canFinish) || finishes(s.no(), canFinish)) {
                    canFinish.add(s.key());
                    grew = true;
                }
            }
        }
        for (Step s : steps) {
            if (reached.contains(s.key()) && !canFinish.contains(s.key())) {
                problems.add("From check " + position.get(s.key())
                        + " the answers only go round in a loop; give one of them an outcome.");
            }
        }
        return problems;
    }

    private static boolean finishes(Branch b, Set<String> canFinish) {
        return b.ends() || canFinish.contains(b.nextKey());
    }

    private static void branch(List<String> problems, String check, String answer, String ownKey, Branch b,
                               Map<String, Integer> position) {
        if (b == null || (b.nextKey() == null) == (b.outcome() == null)) {
            problems.add(check + ": say where \"" + answer + "\" leads - another check or an outcome.");
            return;
        }
        if (b.nextKey() != null) {
            if (!position.containsKey(b.nextKey())) {
                problems.add(check + ": \"" + answer + "\" leads to a check that no longer exists.");
            } else if (b.nextKey().equals(ownKey)) {
                problems.add(check + ": \"" + answer + "\" leads back to the same check.");
            }
        }
    }
}
