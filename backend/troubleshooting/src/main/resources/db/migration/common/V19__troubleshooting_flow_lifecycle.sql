-- =====================================================================
--  troubleshooting: guided checks authored in the app (SoW s13, TSA-03)
--
--  A flow is edited as a DRAFT, then PUBLISHED; publishing a new version
--  RETIRES the one before it. Published and retired versions are never edited
--  or deleted: a Captain part-way through a set of checks keeps answering the
--  version they started, and every logged answer still points at the step it
--  answered. Only a draft, which no session can reference, may be discarded.
-- =====================================================================

ALTER TABLE troubleshooting_flow ADD COLUMN status VARCHAR(16);
UPDATE troubleshooting_flow SET status = CASE WHEN published THEN 'PUBLISHED' ELSE 'DRAFT' END;
ALTER TABLE troubleshooting_flow ALTER COLUMN status SET NOT NULL;

DROP INDEX ix_ts_flow_selection;
ALTER TABLE troubleshooting_flow DROP COLUMN published;

ALTER TABLE troubleshooting_flow ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE troubleshooting_flow ADD COLUMN published_by_user_id BIGINT;
ALTER TABLE troubleshooting_flow ADD COLUMN retired_at TIMESTAMP WITH TIME ZONE;

UPDATE troubleshooting_flow SET published_at = created_at WHERE status = 'PUBLISHED';

ALTER TABLE troubleshooting_flow ADD CONSTRAINT ck_ts_flow_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'));
ALTER TABLE troubleshooting_flow ADD CONSTRAINT fk_ts_flow_publisher
    FOREIGN KEY (published_by_user_id) REFERENCES app_user (id);

CREATE INDEX ix_ts_flow_selection ON troubleshooting_flow (equipment_category_id, problem_type_id, status);
CREATE INDEX ix_ts_step_flow_order ON troubleshooting_step (flow_id, display_order);
