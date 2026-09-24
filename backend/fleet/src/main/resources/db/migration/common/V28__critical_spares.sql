-- =====================================================================
--  fleet: critical spares, as the client's own form records them
--
--  The client keeps a "Navigation Equipment - Minimum Spares Recommendation"
--  (GM 2.3.9.9): per equipment, the spare parts that must be held on board,
--  the minimum quantity, whether the vessel currently complies, and remarks -
--  a requisition number, a shelf-life note, whatever the follow-up is.
--
--  The platform already held most of that in replacement_part: the vessel, the
--  equipment the part belongs to, what is on the shelf and what the minimum is,
--  with an alert when a count drops below it. Three columns were missing, and
--  one of them is the reason this table could not represent their form.
--
--  minimum_quantity stays an integer because the below-minimum alert is
--  arithmetic; minimum_note carries the requirement as the form words it -
--  "2 pcs each athwartship, fore & aft and Flinders bar" is not a number and
--  never will be. Keeping only the number would misrepresent the document;
--  keeping only the text would silently kill the alerting.
-- =====================================================================

-- Marks a part as one of the minimum spares the form requires, as opposed to
-- an ordinary consumable someone recorded. Existing rows are ordinary.
ALTER TABLE replacement_part ADD COLUMN critical BOOLEAN NOT NULL DEFAULT FALSE;

-- The requirement in the client's own words, where a number cannot say it.
ALTER TABLE replacement_part ADD COLUMN minimum_note VARCHAR(300);

-- The form's Compliance column. Null means nobody has assessed it yet, which
-- is different from NA ("this vessel does not carry that equipment").
ALTER TABLE replacement_part ADD COLUMN compliance VARCHAR(8);

-- "Information on applicability, status, follow up actions / requisition no.
-- in case of non-compliance."
ALTER TABLE replacement_part ADD COLUMN remarks VARCHAR(1000);

ALTER TABLE replacement_part ADD CONSTRAINT ck_part_compliance
    CHECK (compliance IS NULL OR compliance IN ('YES', 'NO', 'NA'));

-- The critical-spares list for one vessel is read as a list, in equipment
-- order; the monthly inventory check is exactly that read.
CREATE INDEX ix_part_critical ON replacement_part (vessel_id, critical);
