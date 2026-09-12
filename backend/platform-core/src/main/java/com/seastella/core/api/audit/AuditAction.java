package com.seastella.core.api.audit;

/**
 * The audited action vocabulary (AUD-05 through AUD-14).
 *
 * <p>Constants rather than free strings, so the audit report can group
 * reliably and so a typo cannot quietly create an action that no report shows.
 */
public final class AuditAction {

    private AuditAction() {
    }

    // Identity and provisioning (AUD-12)
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";
    public static final String VESSEL_ASSIGNED = "VESSEL_ASSIGNED";
    public static final String VESSEL_UNASSIGNED = "VESSEL_UNASSIGNED";
    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    // Fleet and master data (AUD-05, AUD-06)
    public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";
    public static final String ORGANIZATION_UPDATED = "ORGANIZATION_UPDATED";
    public static final String VESSEL_CREATED = "VESSEL_CREATED";
    public static final String VESSEL_UPDATED = "VESSEL_UPDATED";
    public static final String SPARE_CREATED = "SPARE_CREATED";
    public static final String SPARE_UPDATED = "SPARE_UPDATED";
    public static final String SPARE_DELETED = "SPARE_DELETED";
    public static final String RUNNING_HOURS_RECORDED = "RUNNING_HOURS_RECORDED";
    public static final String PART_STOCK_CHANGED = "PART_STOCK_CHANGED";

    // Maintenance (AUD-13, AUD-14)
    public static final String SERVICE_DATE_CHANGED = "SERVICE_DATE_CHANGED";
    public static final String MAINTENANCE_RULE_CHANGED = "MAINTENANCE_RULE_CHANGED";
    public static final String THRESHOLD_CHANGED = "THRESHOLD_CHANGED";
    public static final String CONFIGURATION_CHANGED = "CONFIGURATION_CHANGED";

    // Service request workflow (AUD-10, AUD-11)
    public static final String REQUEST_RAISED = "REQUEST_RAISED";
    public static final String REQUEST_TRANSITIONED = "REQUEST_TRANSITIONED";
    public static final String REQUEST_APPROVED = "REQUEST_APPROVED";
    public static final String REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String CLARIFICATION_REQUESTED = "CLARIFICATION_REQUESTED";
    public static final String ENGINEER_ASSIGNED = "ENGINEER_ASSIGNED";
    public static final String COMPLETION_REPORTED = "COMPLETION_REPORTED";
    public static final String REQUEST_COMPLETED = "REQUEST_COMPLETED";
    public static final String REQUEST_CLOSED_NO_COST = "REQUEST_CLOSED_NO_COST";
    public static final String TROUBLESHOOTING_STEP_ANSWERED = "TROUBLESHOOTING_STEP_ANSWERED";
    public static final String ESCALATED_TO_LIVE_AGENT = "ESCALATED_TO_LIVE_AGENT";

    // Invoice (AUD-09)
    public static final String INVOICE_RAISED = "INVOICE_RAISED";
    public static final String INVOICE_ACCEPTED = "INVOICE_ACCEPTED";
    public static final String INVOICE_REJECTED = "INVOICE_REJECTED";
    public static final String INVOICE_QUERIED = "INVOICE_QUERIED";

    // Documents and import (AUD-07, AUD-08)
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";
    public static final String DOCUMENT_DELETED = "DOCUMENT_DELETED";
    public static final String IMPORT_UPLOADED = "IMPORT_UPLOADED";
    public static final String IMPORT_COMMITTED = "IMPORT_COMMITTED";
    public static final String IMPORT_REJECTED = "IMPORT_REJECTED";
}
