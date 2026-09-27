-- =====================================================================
--  maintenance: the platform's default colour bands (SoW s11)
--
--  The engine has always classified with these values when no row exists,
--  but the rows themselves were only ever written by the demo seed. With the
--  seed gone a new installation had none, and the Maintenance bands page -
--  which edits the platform default - had nothing to show. They are
--  reference data, like the equipment categories, so they belong here.
--  Written only where no platform default exists yet.
-- =====================================================================

INSERT INTO maintenance_threshold (organization_id, status_code, min_days, max_days, active, created_at, version)
SELECT NULL, 'URGENT', 1, 9, TRUE, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM maintenance_threshold WHERE organization_id IS NULL AND status_code = 'URGENT');

INSERT INTO maintenance_threshold (organization_id, status_code, min_days, max_days, active, created_at, version)
SELECT NULL, 'APPROACHING', 10, 15, TRUE, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (
    SELECT 1 FROM maintenance_threshold WHERE organization_id IS NULL AND status_code = 'APPROACHING');
