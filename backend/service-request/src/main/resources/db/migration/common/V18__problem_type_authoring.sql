-- =====================================================================
--  service-request: problem types maintained by the Platform Admin
--
--  SoW s13: "Seastella can add new Spare/problem combinations without code
--  changes". Until now problem types came only from the demo seed, so a
--  production database had none. A problem type is never deleted: past
--  requests name it. Retiring one hides it from Captains choosing a problem.
-- =====================================================================

ALTER TABLE problem_type ADD COLUMN active BOOLEAN DEFAULT TRUE NOT NULL;
