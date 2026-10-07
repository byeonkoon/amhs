-- The soak run's site: the large layout (revision 3) in use, simulated, dispatching by itself, with
-- five OHT and five AGV in automatic work and the rest of the fleet out of use. Applied to a fresh
-- soak database after the server has migrated it and before the measured run starts. Never applied
-- to a development or operating database.
UPDATE topology.map_revision SET status='RETIRED' WHERE site_id=1 AND status='ACTIVE';
UPDATE topology.map_revision SET status='ACTIVE' WHERE site_id=1 AND revision=3;
UPDATE platform.site_operation SET run_mode='SIMULATION',auto_dispatch=TRUE,mode='RUNNING',sequence_running=TRUE,
 restart_check_since=NULL WHERE site_id=1;
WITH rev AS (SELECT id FROM topology.map_revision WHERE site_id=1 AND revision=3),
ranked AS (SELECT v.id,row_number() OVER (PARTITION BY v.v_type ORDER BY v.code) AS n
           FROM fleet.vehicle_profile v JOIN rev ON v.revision_id=rev.id)
UPDATE fleet.vehicle_profile p SET enabled=(ranked.n<=5),assignment_enabled=(ranked.n<=5)
 FROM ranked WHERE p.id=ranked.id;
SELECT v_type,count(*) FILTER (WHERE enabled) AS in_use,count(*) AS configured
 FROM fleet.vehicle_profile WHERE revision_id=(SELECT id FROM topology.map_revision WHERE site_id=1 AND revision=3)
 GROUP BY v_type ORDER BY v_type;
