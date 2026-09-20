package com.seastella.core.api.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the small flat JSON objects stored as an audit entry's before and
 * after values. Nulls are left out; everything else is written as a string, so
 * an amount is recorded exactly as it was entered rather than as a float.
 */
public final class AuditJson {

    private AuditJson() {
    }

    /** @param keysAndValues alternating key, value pairs */
    public static String of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must pair up");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                fields.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
            }
        }
        if (fields.isEmpty()) return null;

        StringBuilder out = new StringBuilder("{");
        fields.forEach((k, v) -> {
            if (out.length() > 1) out.append(',');
            out.append('"').append(escape(k)).append("\":\"").append(escape(String.valueOf(v))).append('"');
        });
        return out.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
