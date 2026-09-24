-- =====================================================================
--  masterdata-import: a staged row says what kind of thing it is
--
--  Until now an import was equipment only, and every row meant a spare in
--  the VMP tree. Clients send one workbook holding the vessel's particulars,
--  its equipment, and a minimum-spares table, so a row now carries what it
--  is and the commit applies each kind to its own place.
--
--  Existing rows are equipment: that is all they could have been.
-- =====================================================================

ALTER TABLE import_row ADD COLUMN kind VARCHAR(16) DEFAULT 'EQUIPMENT' NOT NULL;

-- The critical spare a row created, so a re-import updates it rather than
-- adding a second copy. vmp_ref/spare_id stay for equipment; part_id is the
-- same idea for the parts table.
ALTER TABLE import_row ADD COLUMN part_id BIGINT;

-- Which equipment a critical spare belongs to, as the sheet named it. Kept as
-- written rather than resolved at upload: the equipment may arrive in the same
-- file and not exist yet when the row is staged.
ALTER TABLE import_row ADD COLUMN equipment_label VARCHAR(200);

ALTER TABLE import_row ADD CONSTRAINT ck_import_row_kind CHECK (
    kind IN ('VESSEL', 'EQUIPMENT', 'CRITICAL_SPARE'));
