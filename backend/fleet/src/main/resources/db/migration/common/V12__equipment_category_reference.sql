-- =====================================================================
--  fleet: equipment categories as reference data
--
--  The SoW s9.4 categories were created only by the demo seeder, so a
--  production database (seeding off) had none - and a new vessel's standard
--  bridge fit had no categories to hang its spares on. They are platform
--  reference data, so they belong in a migration. Existing rows are kept.
-- =====================================================================

INSERT INTO equipment_category (code, name, display_order, created_at, version)
SELECT v.code, v.name, v.display_order, CURRENT_TIMESTAMP, 0
FROM (VALUES
    ('AIS', 'AIS', 1),
    ('VHF', 'VHF', 2),
    ('MFHF', 'MF/HF with DSC and NBDP', 3),
    ('SATC', 'SAT-C', 4),
    ('LRIT', 'LRIT', 5),
    ('SSAS', 'SSAS', 6),
    ('NAVTEX', 'NAVTEX', 7),
    ('EPIRB', 'EPIRB', 8),
    ('SART', 'SART', 9),
    ('GMDSS_WT', 'GMDSS Walkie-Talkie', 10),
    ('VDR', 'VDR / SVDR', 11),
    ('GYRO', 'Gyro', 12),
    ('RADAR', 'Radar', 13),
    ('ECDIS', 'ECDIS', 14),
    ('SPEED_LOG', 'Speed Log', 15),
    ('ECHO_SOUNDER', 'Echo Sounder', 16),
    ('ANEMOMETER', 'Anemometer', 17),
    ('AUTOPILOT', 'Autopilot', 18),
    ('BNWAS', 'BNWAS', 19),
    ('ITU_PUB', 'ITU Publications', 20),
    ('GPS', 'GPS', 21)
) AS v (code, name, display_order)
WHERE NOT EXISTS (SELECT 1 FROM equipment_category e WHERE e.code = v.code);
