-- Runs before any migration, every time, on whatever version the database is already at.
--
-- One applied migration seeds sample equipment at fixed ids and then points the sample ports at
-- those ids. The insert skips a row whose code a site already configured under a different id, and
-- the statement after it still points a port at the id that was skipped: a foreign key to nothing,
-- the migration rolls back, and the application does not start. A later migration repairs exactly
-- that, but it is a later migration -- Flyway runs versions in order, so it never gets a turn. A
-- repair that can only run after the failure is not a repair.
--
-- A callback is the only thing that runs earlier than a fixed version, so the link is made here, by
-- code, which is what pointing at those ids always meant. The migration that follows links only
-- ports that are still unlinked, so once these are linked it finds nothing to do and cannot name an
-- id that does not exist. Ports whose codes a site never configured are left alone and seeded as
-- before.
--
-- Everything here is guarded and repeatable: it runs against an empty database where none of these
-- tables exist yet, and against one already at the newest version, and must be silent in both.
DO $$
DECLARE linked INT; cleared INT;
BEGIN
  IF to_regclass('equipment.unit') IS NULL OR to_regclass('topology.port') IS NULL THEN RETURN; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema='topology' AND table_name='port' AND column_name='equipment_unit_id')
  THEN RETURN; END IF;

  -- A port pointing at equipment that is not there is a fault waiting to surface somewhere else. A
  -- port pointing at nothing is an honest "not linked yet", so that is what it becomes.
  UPDATE topology.port p SET equipment_unit_id=NULL
   WHERE p.equipment_unit_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM equipment.unit u WHERE u.id=p.equipment_unit_id);
  GET DIAGNOSTICS cleared=ROW_COUNT;

  UPDATE topology.port p SET equipment_unit_id=u.id
   FROM equipment.unit u
   WHERE u.site_id=p.site_id AND u.revision_id=p.revision_id
     AND p.equipment_unit_id IS NULL
     AND u.code = CASE p.code
          WHEN 'OHT-A' THEN 'STK-01' WHEN 'OHT-D' THEN 'STK-01'
          WHEN 'OHT-B' THEN 'EQP-01' WHEN 'OHT-C' THEN 'EQP-02' WHEN 'OHT-E' THEN 'BUF-01'
          WHEN 'AGV-A' THEN 'STK-11' WHEN 'AGV-B' THEN 'EQP-11' WHEN 'AGV-C' THEN 'BUF-11' END;
  GET DIAGNOSTICS linked=ROW_COUNT;

  IF cleared>0 OR linked>0 THEN
    RAISE NOTICE 'sample port links: % cleared, % linked by code before migrating',cleared,linked;
  END IF;
END $$;
