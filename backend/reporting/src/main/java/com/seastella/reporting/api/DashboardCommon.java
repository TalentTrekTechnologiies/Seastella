package com.seastella.reporting.api;

import java.time.Instant;
import java.util.List;

/**
 * Shared shapes used across the role dashboards.
 *
 * <p>Records, not entities. JPA entities are never returned from a controller:
 * they carry lazy associations, they change shape when the schema does, and
 * they have no way to omit a field one role may not see.
 */
public final class DashboardCommon {

    private DashboardCommon() {}

    /** A headline figure, with enough context to be drilled into. */
    public record Kpi(String key, String label, long value, String unit, String trendHint) {
        public static Kpi of(String key, String label, long value) {
            return new Kpi(key, label, value, null, null);
        }
        public static Kpi of(String key, String label, long value, String unit) {
            return new Kpi(key, label, value, unit, null);
        }
    }

    /** One slice of a distribution - a donut, a stacked bar, a status strip. */
    public record Slice(String key, String label, long value, String colour) {}

    /** A named distribution, ready to chart without further shaping. */
    public record Distribution(String key, String label, long total, List<Slice> slices) {
        public static Distribution of(String key, String label, List<Slice> slices) {
            return new Distribution(key, label,
                    slices.stream().mapToLong(Slice::value).sum(), slices);
        }
    }

    /**
     * A queue of work requiring action.
     *
     * @param actionLabel what the role is expected to do, e.g. "Approve or reject"
     * @param blocked     why the queue cannot be actioned yet, if applicable
     */
    public record ActionQueue<T>(
            String key, String label, String actionLabel, long total,
            List<T> items, String blocked) {}

    /** Pagination envelope for lists that grow. */
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> Page<T> of(List<T> content, int page, int size, long total) {
            int pages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
            return new Page<>(content, page, size, total, pages);
        }
    }

    /** Envelope every dashboard response carries. */
    public record Meta(
            String role, String scopeKind, Long organizationId, String organizationName,
            int vesselsInScope, Instant generatedAt, boolean financialsVisible) {}
}
