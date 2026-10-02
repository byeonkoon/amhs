-- Sample equipment was inserted with fixed ids under ON CONFLICT DO NOTHING, and the next
-- statement pointed ports at those ids. Without a conflict target that clause also covers the
-- code uniqueness constraint, so a site that had already configured equipment with one of those
-- codes skipped the row and then violated the foreign key pointing at it: the migration failed and
-- the application would not start.
--
-- The link is restored here by code rather than by id, which is what it always meant, and any
-- port left pointing at equipment that does not exist is cleared rather than left dangling. A
-- port with no equipment is an honest "not linked yet"; one pointing at nothing is a fault
-- waiting to surface somewhere else.
UPDATE topology.port p SET equipment_unit_id=NULL
 WHERE p.equipment_unit_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM equipment.unit u WHERE u.id=p.equipment_unit_id);

UPDATE topology.port p SET equipment_unit_id=u.id
 FROM equipment.unit u
 WHERE u.site_id=p.site_id AND u.revision_id=p.revision_id
   AND p.equipment_unit_id IS NULL
   AND u.code = CASE p.code
        WHEN 'OHT-A' THEN 'STK-01' WHEN 'OHT-D' THEN 'STK-01'
        WHEN 'OHT-B' THEN 'EQP-01' WHEN 'OHT-C' THEN 'EQP-02' WHEN 'OHT-E' THEN 'BUF-01'
        WHEN 'AGV-A' THEN 'STK-11' WHEN 'AGV-B' THEN 'EQP-11' WHEN 'AGV-C' THEN 'BUF-11' END;
