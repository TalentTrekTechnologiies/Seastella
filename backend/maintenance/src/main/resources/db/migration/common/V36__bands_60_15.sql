-- =====================================================================
--  maintenance: the client's warning notice - yellow at 60 days, red at 15
--
--  The bands shipped as yellow 10-15 and orange 1-9. In service that is far
--  too late: the parts for a marine overhaul are ordered weeks ahead and a
--  yard slot is booked further ahead still, so a warning five days before a
--  due date tells a superintendent something they can no longer act on.
--
--  The client asked for two notices instead, and only two:
--
--      more than 60 days   green   normal
--      16 to 60 days       yellow  approaching   - one alert on entry
--      1 to 15 days        red     urgent        - one alert on entry
--      due today           red     due
--      past due            red     overdue
--
--  Orange is retired: the client's own escalation has two steps, and a third
--  colour between them would be the platform inventing a distinction nobody
--  downstream acts on differently.
--
--  Only the platform default rows move. An organization that set its own
--  bands chose them deliberately, and this is not the place to overrule it.
--
--  These are still editable on the Maintenance bands page - this migration
--  changes what a fleet starts with, not what it is held to.
-- =====================================================================

UPDATE maintenance_threshold
   SET min_days = 1, max_days = 15, updated_at = CURRENT_TIMESTAMP
 WHERE organization_id IS NULL AND status_code = 'URGENT';

UPDATE maintenance_threshold
   SET min_days = 16, max_days = 60, updated_at = CURRENT_TIMESTAMP
 WHERE organization_id IS NULL AND status_code = 'APPROACHING';

-- Belt and braces: an installation that somehow reached this point without
-- the V34 defaults still ends up with the bands the client asked for.
INSERT INTO maintenance_threshold (organization_id, status_code, min_days, max_days, active, created_at, version)
SELECT NULL, 'URGENT', 1, 15, TRUE, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM maintenance_threshold WHERE organization_id IS NULL AND status_code = 'URGENT');

INSERT INTO maintenance_threshold (organization_id, status_code, min_days, max_days, active, created_at, version)
SELECT NULL, 'APPROACHING', 16, 60, TRUE, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM maintenance_threshold WHERE organization_id IS NULL AND status_code = 'APPROACHING');
