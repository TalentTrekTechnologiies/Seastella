package com.seastella.servicerequest.api;

/**
 * Whether a request may leave troubleshooting yet.
 *
 * <p>SoW s6.1: the assistant engages on submission, and escalation or approval
 * follow <em>after</em> its checks. The troubleshooting module sits above this
 * one, so it answers through this port - as the invoice module answers
 * {@link InvoiceGateQuery} - and the workflow does not depend on it.
 */
public interface TroubleshootingGate {

    /**
     * @return null when the request may be submitted or escalated; otherwise
     *         the reason it may not, in words for the Captain
     */
    String blockingReason(Long serviceRequestId);
}
