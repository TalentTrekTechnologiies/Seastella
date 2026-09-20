package com.seastella.notification.internal;

import com.seastella.fleet.api.FleetEvents;
import com.seastella.identity.api.Role;
import com.seastella.maintenance.api.DueAssessment;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceEvents;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.seastella.notification.internal.Notification.Category.ACTION;
import static com.seastella.notification.internal.Notification.Category.MAINTENANCE;
import static com.seastella.notification.internal.Notification.Category.UPDATE;

/**
 * What each alert says, per event and per recipient role.
 *
 * <p>Written for the person reading it: what happened, on which equipment and
 * vessel, and - where it is theirs to act on - what to do next. The same event
 * reads differently to a Ship Manager who must approve it and a Coordinator
 * who only needs to know it is coming.
 *
 * <p><b>No alert carries an invoice amount.</b> SoW s12 keeps cost from the
 * Captain and the Service Engineer, and an email can be forwarded anywhere, so
 * the amount stays in the app behind the role check.
 */
final class AlertMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int MAX_SPARES_LISTED = 8;

    private AlertMessages() {
    }

    record Message(Notification.Category category, String title, String body) {}

    /** The request the event is about, with the names an alert needs. */
    record RequestContext(String number, String spare, String vessel, String title, String actor, String reason) {}

    /**
     * A part has gone below the minimum the vessel must hold (SPR-14). One
     * message for everyone told: the fact is the same whoever reads it, and
     * what to do about it differs by vessel, not by role.
     */
    static Message forPartShortage(FleetEvents.PartStockChanged event) {
        String what = event.partName()
                + (event.partNumber() == null ? "" : " (" + event.partNumber() + ")");
        return new Message(ACTION, "Replacement part below minimum: " + event.partName(),
                what + " on " + event.vesselName() + " is down to " + event.quantityOnHand()
                        + " of a minimum " + event.minimumQuantity() + ". Order a replacement before it is needed.");
    }

    /** @return null when this role has nothing to be told for this action */
    static Message forRequest(ServiceRequestAction action, Role role, RequestContext c) {
        String on = c.spare() + " on " + c.vessel();
        String quoted = "“" + c.title() + "”";

        return switch (action) {
            case RAISE -> role == Role.SHIP_MANAGER
                    ? new Message(UPDATE, "New service request on " + c.vessel(),
                    c.actor() + " raised " + c.number() + " for " + c.spare() + ": " + quoted
                            + ". Troubleshooting has started; it comes to you for approval if it is not resolved.")
                    : null;

            case SUBMIT_FOR_APPROVAL -> role == Role.SHIP_MANAGER
                    ? new Message(ACTION, "Approval needed: " + c.number(),
                    on + " — " + quoted + ". The Captain has finished troubleshooting; the guided checks are on the request. "
                            + "Approve it, reject it or ask for clarification.")
                    : null;

            case RESUBMIT -> role == Role.SHIP_MANAGER
                    ? new Message(ACTION, "Resubmitted for approval: " + c.number(),
                    "The Captain answered your clarification on " + on + ". Review it again.")
                    : null;

            case ESCALATE_TO_LIVE_AGENT -> role == Role.SERVICE_COORDINATOR
                    ? new Message(ACTION, "Live agent requested: " + c.number(),
                    "The Captain escalated " + on + " — " + quoted + " — for live help.")
                    : null;

            case APPROVE_OPERATIONAL -> switch (role) {
                case CAPTAIN -> new Message(UPDATE, "Request approved: " + c.number(),
                        c.actor() + " approved your request for " + c.spare()
                                + ". It is now with the Service Coordinator.");
                case SERVICE_COORDINATOR -> new Message(ACTION, "Approved request to handle: " + c.number(),
                        on + " — " + quoted + ". Close it without cost if it is already resolved, or raise an invoice.");
                default -> null;
            };

            case REJECT -> role == Role.CAPTAIN
                    ? new Message(UPDATE, "Request rejected: " + c.number(),
                    c.actor() + " rejected your request for " + c.spare() + "." + reason(c))
                    : null;

            case REQUEST_CLARIFICATION -> role == Role.CAPTAIN
                    ? new Message(ACTION, "Clarification requested: " + c.number(),
                    c.actor() + " needs more on " + c.spare() + "." + reason(c) + " Answer on the request and resubmit.")
                    : null;

            case CLOSE_NO_COST -> role == Role.CAPTAIN || role == Role.SHIP_MANAGER
                    ? new Message(UPDATE, "Closed without cost: " + c.number(),
                    "The Service Coordinator closed " + on + " as resolved. No invoice was raised." + reason(c))
                    : null;

            case RAISE_INVOICE -> role == Role.SHIP_MANAGER
                    ? new Message(ACTION, "Invoice to review: " + c.number(),
                    "The Service Coordinator raised an invoice for " + on + ". Accept, reject or query it — "
                            + "no engineer is assigned until you accept.")
                    : null;

            case ACCEPT_INVOICE -> switch (role) {
                case SERVICE_COORDINATOR -> new Message(ACTION, "Invoice accepted: " + c.number(),
                        c.actor() + " accepted the invoice for " + on + ". You can now assign a Service Engineer.");
                case CAPTAIN -> new Message(UPDATE, "Service authorised: " + c.number(),
                        "The Ship Manager authorised the service on " + c.spare() + ". A Service Engineer will be assigned.");
                default -> null;
            };

            case REJECT_INVOICE -> role == Role.SERVICE_COORDINATOR
                    ? new Message(ACTION, "Invoice rejected: " + c.number(),
                    c.actor() + " rejected the invoice for " + on + "." + reason(c))
                    : null;

            case QUERY_INVOICE -> role == Role.SERVICE_COORDINATOR
                    ? new Message(ACTION, "Invoice queried: " + c.number(),
                    c.actor() + " has a question on the invoice for " + on + "." + reason(c))
                    : null;

            case ASSIGN_ENGINEER -> role == Role.SERVICE_ENGINEER
                    ? new Message(ACTION, "New job assigned: " + c.number(),
                    on + " — " + quoted + ". The job is authorised; open it for the full context.")
                    : null;

            case SUBMIT_COMPLETION -> role == Role.SERVICE_COORDINATOR
                    ? new Message(ACTION, "Completion report received: " + c.number(),
                    c.actor() + " reported the work on " + on
                            + ". Check it and mark the request completed to update the Ship Manager.")
                    : null;

            case COMPLETE -> switch (role) {
                case SHIP_MANAGER -> new Message(UPDATE, "Service completed: " + c.number(),
                        "The Service Coordinator confirmed the service on " + on + " is complete.");
                case CAPTAIN -> new Message(UPDATE, "Service completed: " + c.number(),
                        "The service on " + c.spare() + " is complete.");
                default -> null;
            };

            default -> null;
        };
    }

    /** SoW s11: the Coordinator hears how the guided checks ended, before approval. */
    static Message forTroubleshooting(TroubleshootingOutcome outcome, Role role, RequestContext c) {
        if (role != Role.SERVICE_COORDINATOR) return null;
        String on = c.spare() + " on " + c.vessel();
        return switch (outcome) {
            case RESOLVED -> new Message(UPDATE, "Checks resolved it: " + c.number(),
                    "The Captain's guided checks resolved " + on + ". Once approved it comes to you; "
                            + "it can likely be closed without cost.");
            case TEMPORARY_FIX -> new Message(UPDATE, "Temporary fix in place: " + c.number(),
                    on + " is working on a temporary fix after the guided checks. Once approved, decide whether "
                            + "a service visit is needed.");
            case UNRESOLVED -> new Message(UPDATE, "Checks did not resolve it: " + c.number(),
                    "The guided checks did not resolve " + on + ". Once the Ship Manager approves it, it comes to you.");
        };
    }

    /** One alert per vessel: a single spare by name, several as a grouped list. */
    static Message forMaintenance(MaintenanceEvents.DueStatusChanged event) {
        List<MaintenanceEvents.Change> changes = event.changes().stream()
                .sorted(Comparator.comparingInt((MaintenanceEvents.Change c) -> -MaintenanceEvents.severity(c.to()))
                        .thenComparing(c -> c.daysRemaining() == null ? Integer.MAX_VALUE : c.daysRemaining()))
                .toList();

        if (changes.size() == 1) {
            MaintenanceEvents.Change c = changes.get(0);
            return new Message(MAINTENANCE, headline(c.to()) + ": " + c.spareName(),
                    event.vesselName() + " · VMP " + c.sparePath() + ". " + dueLine(c) + ".");
        }

        StringBuilder body = new StringBuilder();
        int listed = 0;
        for (DueStatus status : List.of(DueStatus.OVERDUE, DueStatus.DUE, DueStatus.URGENT, DueStatus.APPROACHING)) {
            List<MaintenanceEvents.Change> inBand = changes.stream().filter(c -> c.to() == status).toList();
            if (inBand.isEmpty() || listed >= MAX_SPARES_LISTED) continue;

            List<MaintenanceEvents.Change> shown = inBand.stream().limit(MAX_SPARES_LISTED - listed).toList();
            listed += shown.size();
            if (!body.isEmpty()) body.append(' ');
            body.append(status.label()).append(": ")
                    .append(shown.stream().map(AlertMessages::shortLine).collect(Collectors.joining(", ")))
                    .append('.');
        }
        if (changes.size() > listed) {
            body.append(" And ").append(changes.size() - listed).append(" more.");
        }

        return new Message(MAINTENANCE, changes.size() + " maintenance alerts on " + event.vesselName(),
                body.toString());
    }

    private static String headline(DueStatus status) {
        return switch (status) {
            case OVERDUE -> "Maintenance overdue";
            case DUE -> "Maintenance due today";
            case URGENT -> "Maintenance urgent";
            case APPROACHING -> "Maintenance approaching";
            default -> "Maintenance status changed";
        };
    }

    private static String dueLine(MaintenanceEvents.Change c) {
        String basis = c.basis() == DueAssessment.Basis.RUNNING_HOURS ? " by running hours" : "";
        Integer d = c.daysRemaining();
        LocalDate date = c.nextDueDate();
        if (d == null || date == null) return "Service status is " + c.to().label().toLowerCase();
        if (d < 0) return "Service was due " + days(-d) + " ago" + basis + " (" + DATE.format(date) + ")";
        if (d == 0) return "Service is due today" + basis;
        return "Service due in " + days(d) + basis + " (" + DATE.format(date) + ")";
    }

    private static String shortLine(MaintenanceEvents.Change c) {
        Integer d = c.daysRemaining();
        if (d == null) return c.spareName();
        if (d < 0) return c.spareName() + " (" + days(-d) + " over)";
        if (d == 0) return c.spareName() + " (today)";
        return c.spareName() + " (in " + days(d) + ")";
    }

    private static String days(int n) {
        return n + (n == 1 ? " day" : " days");
    }

    private static String reason(RequestContext c) {
        return c.reason() == null || c.reason().isBlank() ? "" : " “" + c.reason().trim() + "”";
    }
}
