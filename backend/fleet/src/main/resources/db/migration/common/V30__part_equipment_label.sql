-- =====================================================================
--  fleet: the equipment a critical spare belongs to, as the form names it
--
--  A minimum-spares form names equipment in its own words - "Magnetic
--  Compass", "Aldis Lamp" - and some of it is not on the vessel's equipment
--  list at all. Such a spare used to be hung on whatever equipment sat at the
--  form's line number, which put compass bulbs under AIS. Now it stays
--  unlinked, and this column keeps the name the form gave, so the list still
--  reads the way the form does.
-- =====================================================================

ALTER TABLE replacement_part ADD COLUMN equipment_label VARCHAR(200);
