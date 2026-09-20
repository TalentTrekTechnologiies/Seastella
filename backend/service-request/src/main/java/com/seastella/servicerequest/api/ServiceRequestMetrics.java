package com.seastella.servicerequest.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service-request aggregates and queues for the dashboards.
 *
 * <p>Queues are the useful part: a dashboard that only shows counts tells a
 * Ship Manager that four requests await approval but not which ones, so every
 * count that represents work has a matching list method with a stable
 * drill-down id.
 */
public interface ServiceRequestMetrics {

    Map<ServiceRequestStatus, Long> countsByStatus(Set<Long> vesselIds);

    Map<ServiceRequestStatus, Long> countsByStatusPlatformWide();

    Map<Priority, Long> openCountsByPriority(Set<Long> vesselIds);

    /** A work queue: requests in the given statuses, most urgent first. */
    List<RequestSummary> queue(Set<Long> vesselIds, Collection<ServiceRequestStatus> statuses, int limit);

    /** Platform-wide queue. Platform Admin only. */
    List<RequestSummary> queuePlatformWide(Collection<ServiceRequestStatus> statuses, int limit);

    /** An engineer's own jobs, and only their own. */
    List<RequestSummary> forEngineer(Long engineerUserId, Collection<ServiceRequestStatus> statuses);

    long countByStatuses(Set<Long> vesselIds, Collection<ServiceRequestStatus> statuses);

    long openCount(Set<Long> vesselIds);

    /**
     * Requests the Ship Manager operationally approved on or after {@code since}
     * - the "requests approved this period" figure of SoW s8.1, which sits
     * beside the pending count so a Technical Head can see whether approvals
     * are keeping up with what is being raised.
     */
    long approvedSince(Set<Long> vesselIds, java.time.Instant since);

    /** Per-vessel open request counts, for the fleet table. */
    Map<Long, Long> openCountPerVessel(Set<Long> vesselIds);

    /**
     * Requests closed without cost versus those needing an engineer visit.
     * SoW s8.1 puts this on the Technical Head's dashboard - it is the figure
     * that shows whether troubleshooting is paying for itself.
     */
    ResolutionSplit resolutionSplit(Set<Long> vesselIds);

    /** Mean hours from raise to close, over recently closed requests. */
    Double averageTurnaroundHours(Set<Long> vesselIds, int overLastDays);

    /** Recent transitions across the given vessels, newest first. */
    List<ActivityItem> recentActivity(Set<Long> vesselIds, int limit);

    /** Recent transitions across the whole platform. Platform Admin only. */
    List<ActivityItem> recentActivityPlatformWide(int limit);

    /** One request's full transition history, for the timeline. */
    List<ActivityItem> history(Long serviceRequestId);

    /** One request, with vessel, spare and people resolved. */
    Optional<RequestSummary> summary(Long serviceRequestId);

    /** Most recent requests on the given vessels, newest first. */
    List<RequestSummary> recent(Set<Long> vesselIds, int limit);

    /** Most recent requests across the platform, newest first. */
    List<RequestSummary> recentPlatformWide(int limit);

    /** The completion report, visible to the Coordinator and above. */
    CompletionSummary completionReport(Long serviceRequestId);

    record RequestSummary(
            Long id, String requestNumber, Long vesselId, String vesselName,
            Long spareId, String spareName, String sparePath, String categoryCode,
            String title, Priority priority, ServiceRequestStatus status, String statusLabel,
            Long raisedByUserId, String raisedByName,
            Long assignedEngineerUserId, String assignedEngineerName,
            Instant raisedAt, Instant lastUpdatedAt, Integer ageDays) {}

    record ActivityItem(
            Long serviceRequestId, String requestNumber,
            Long organizationId, Long vesselId, String vesselName,
            ServiceRequestStatus fromStatus, ServiceRequestStatus toStatus,
            ServiceRequestAction action, String actionLabel,
            Long actorUserId, String actorName, String actorRole,
            String reason, Instant occurredAt) {}

    record ResolutionSplit(long resolvedWithoutCost, long resolvedByEngineerVisit, long stillOpen) {}

    record CompletionSummary(
            Long serviceRequestId, Long engineerUserId, String engineerName,
            String workPerformed, String partsUsed, String outcome,
            String finalCost, String costVariance, boolean hasVariance,
            String relayNote, Instant reportedAt, Instant reconciledAt) {}
}
