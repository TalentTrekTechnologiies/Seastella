-- ============================================================================
--  fleet: what kind of work a service history entry records
--
--  The QM-F11 pre-survey form asks for the last SSD replacement on each ECDIS
--  and the last sphere replacement on each gyro. Neither is a service date, so
--  an entry says which it is. Everything already recorded was a service.
-- ============================================================================

ALTER TABLE spare_service_record ADD COLUMN kind VARCHAR(24) DEFAULT 'SERVICE' NOT NULL;
ALTER TABLE spare_service_record ADD CONSTRAINT ck_service_record_kind
    CHECK (kind IN ('SERVICE', 'REPLACEMENT', 'SPHERE_REPLACEMENT'));

-- ============================================================================
--  fleet: documents the platform issued itself
--
--  An SBMC or a pre-survey form filed on the vessel belongs to its own record
--  (vessel_certificate, pre_survey_form). It may not be removed or replaced
--  as an ordinary upload: a certificate changes only by revoking it or by
--  issuing the next one, so its record and its file never disagree.
-- ============================================================================

ALTER TABLE document ADD COLUMN issued_by_platform BOOLEAN DEFAULT FALSE NOT NULL;
