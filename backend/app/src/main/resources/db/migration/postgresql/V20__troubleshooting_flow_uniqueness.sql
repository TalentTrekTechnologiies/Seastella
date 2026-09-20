-- =====================================================================
--  TSA-03: the database backs the authoring rules the service enforces.
--
--  PostgreSQL only (H2 has no partial indexes). The service checks the same
--  rules first and explains them; these indexes make two administrators
--  publishing at the same moment fail safely instead of leaving two live flows.
-- =====================================================================

-- At most one draft, and at most one published version, of each flow.
CREATE UNIQUE INDEX ux_ts_flow_one_draft
    ON troubleshooting_flow (code) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX ux_ts_flow_one_published
    ON troubleshooting_flow (code) WHERE status = 'PUBLISHED';

-- At most one published flow for each equipment category and problem type
-- (either may be empty: category-wide checks, or the general fallback).
CREATE UNIQUE INDEX ux_ts_flow_one_published_target
    ON troubleshooting_flow (COALESCE(equipment_category_id, 0), COALESCE(problem_type_id, 0))
    WHERE status = 'PUBLISHED';
