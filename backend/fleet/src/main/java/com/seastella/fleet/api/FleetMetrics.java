package com.seastella.fleet.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fleet aggregates for the dashboards.
 *
 * <p>Every method takes an explicit vessel set - already resolved from the
 * caller's {@code AccessScope} - rather than resolving scope itself. Scope is
 * decided once, in one place, and these queries simply honour it; a metrics
 * method that re-derived scope would be a second implementation of the rule
 * that matters most.
 *
 * <p>All aggregates are grouped in SQL. None of them loads rows to count them.
 */
public interface FleetMetrics {

    /** Vessel counts by status, for the given vessels. */
    Map<VesselStatus, Long> vesselStatusBreakdown(Set<Long> vesselIds);

    /** Platform-wide vessel counts by status. Platform Admin only. */
    Map<VesselStatus, Long> vesselStatusBreakdownPlatformWide();

    long spareCount(Set<Long> vesselIds);

    long vesselCount(Set<Long> vesselIds);

    /** Spare counts by criticality, for the fleet-health donut. */
    Map<Criticality, Long> spareCriticalityBreakdown(Set<Long> vesselIds);

    /** Spare counts by equipment category, for category rollups. */
    Map<String, Long> spareCategoryBreakdown(Set<Long> vesselIds);

    List<VesselSummary> vesselSummaries(Set<Long> vesselIds);

    VesselSummary vesselSummary(Long vesselId);

    /** Parts whose on-hand quantity is below the configured minimum. */
    List<PartShortage> partShortages(Set<Long> vesselIds, int limit);

    long partShortageCount(Set<Long> vesselIds);

    /** One vessel's spare tree, ordered by VMP path so nesting reads naturally. */
    List<SpareNode> spareTree(Long vesselId);

    /** A single spare, for drill-down. */
    SpareNode spare(Long spareId);

    long organizationCount();

    List<OrganizationSummary> organizationSummaries();

    // ----------------------------------------------------------- projections

    record VesselSummary(
            Long id, String name, String imoNumber, String vesselType,
            String flag, VesselStatus status, Long organizationId,
            String organizationName, long spareCount) {}

    record OrganizationSummary(
            Long id, String code, String name, long vesselCount, long userCount) {}

    record SpareNode(
            Long id, Long vesselId, String vesselName, Long parentSpareId,
            String path, int depth, String name, String categoryCode, String categoryName,
            String make, String model, String serialNumber,
            boolean tracksRunningHours, String runningHours,
            Criticality criticality, SpareStatus status) {}

    record PartShortage(
            Long id, Long vesselId, String vesselName, String name, String partNumber,
            int quantityOnHand, int minimumQuantity, String location) {}
}
