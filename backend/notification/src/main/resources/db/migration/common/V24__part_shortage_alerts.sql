-- =====================================================================
--  notification: replacement-part shortage alerts (SoW s11; SPR-14)
--
--  "Replacement part below minimum" is in the s11 alert matrix. It fires when
--  a count - or a raised minimum - takes a part below what the vessel must
--  hold, not every night while it stays short: the alert marks the crossing,
--  and the dashboards carry the standing state.
-- =====================================================================

INSERT INTO notification_rule (event_type, recipient_role, in_app, email, active, source_ref, created_at, version)
VALUES
    ('PART_SHORTAGE', 'CAPTAIN',        TRUE, FALSE, TRUE, 'SoW 11: part below minimum',  CURRENT_TIMESTAMP, 0),
    ('PART_SHORTAGE', 'SHIP_MANAGER',   TRUE, TRUE,  TRUE, 'SoW 11: part below minimum',  CURRENT_TIMESTAMP, 0),
    ('PART_SHORTAGE', 'TECHNICAL_HEAD', TRUE, TRUE,  TRUE, 'SoW 11: part below minimum',  CURRENT_TIMESTAMP, 0);
