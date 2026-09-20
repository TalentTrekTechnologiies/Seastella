-- =====================================================================
--  notification: "Automated troubleshooting completed (resolved / unresolved)
--  - notifies the Service Coordinator" (SoW s11)
--
--  Until the assistant existed, submission for approval stood in for this and
--  assumed the checks had failed. The assistant now reports the real outcome,
--  so the Coordinator is told that instead - and only once.
-- =====================================================================

UPDATE notification_rule SET active = FALSE
WHERE event_type = 'SUBMIT_FOR_APPROVAL' AND recipient_role = 'SERVICE_COORDINATOR';

INSERT INTO notification_rule (event_type, recipient_role, in_app, email, active, source_ref, created_at, version)
VALUES ('TROUBLESHOOTING_COMPLETED', 'SERVICE_COORDINATOR', TRUE, TRUE, TRUE,
        'SoW 11: troubleshooting completed', CURRENT_TIMESTAMP, 0);
