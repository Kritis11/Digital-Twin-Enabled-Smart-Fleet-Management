package com.fleettwin.twin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One threshold rule from {@code fleet.rules} in application.yml. Any of the four limits may be omitted.
 * A component can have several rules; its status is the worst of them.
 */
public record Rule(
        String component,
        String metric,
        Double warningAbove,
        Double criticalAbove,
        Double warningBelow,
        Double criticalBelow) {

    public record Finding(Status status, String message) {
    }

    Status evaluate(Double value) {
        if (value == null) {
            return Status.OK;
        }
        if ((criticalAbove != null && value > criticalAbove) || (criticalBelow != null && value < criticalBelow)) {
            return Status.CRITICAL;
        }
        if ((warningAbove != null && value > warningAbove) || (warningBelow != null && value < warningBelow)) {
            return Status.WARNING;
        }
        return Status.OK;
    }

    /** Status per component, in the order components first appear in the rules. */
    public static Map<String, Finding> evaluateAll(List<Rule> rules, Map<String, Double> sensors) {
        Map<String, Finding> out = new LinkedHashMap<>();
        for (Rule rule : rules) {
            Double value = sensors.get(rule.metric());
            Status status = rule.evaluate(value);
            Finding current = out.get(rule.component());
            if (current == null || status.ordinal() > current.status().ordinal()) {
                String message = status == Status.OK ? "ok"
                        : String.format(Locale.ROOT, "%s %s: %s = %.1f", rule.component(), status, rule.metric(), value);
                out.put(rule.component(), new Finding(status, message));
            }
        }
        return out;
    }
}
