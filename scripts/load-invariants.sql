-- What must be true of a floor after a load run, asked of the database rather than of the server.
--
-- The run itself watches three things as it goes (two vehicles on one node, a vehicle standing on
-- a node it does not hold, a deadlock) and counts how often it saw them. These are the ones a
-- watch cannot see: leftovers. A reservation nobody will ever hand back, a vehicle still assigned
-- to work that finished, a command claimed and never settled -- each of them is a plant that looks
-- busy while nothing is happening, and none of them is prevented by a constraint.
--
-- Every row returned is a problem. An empty result is the pass.
--
-- :site is the site, :quiet_minutes how long something has to have been still to count as stuck.
\if :{?site}
\else
 \set site 1
\endif
\if :{?quiet_minutes}
\else
 \set quiet_minutes 3
\endif
-- Only what this run left. A floor can carry leftovers from months ago, and reading those as this
-- run's answer is how a passing run looks like a failing one -- or, worse, the other way round.
\if :{?since}
\else
 \set since '-infinity'
\endif

\echo '== two vehicles standing on one node (none expected)'
SELECT node_id,count(*) AS vehicles,string_agg(vehicle_id::text,',' ORDER BY vehicle_id) AS which
 FROM fleet.vehicle_state
 WHERE site_id=:site AND node_id IS NOT NULL AND x_mm IS NULL AND y_mm IS NULL
 GROUP BY node_id HAVING count(*)>1;

-- A reservation is handed back by having `released_at` set, not by the row going away: the history
-- of who held what is the only way to read an incident afterwards. So every question here is about
-- rows where it is still null. Asking without that condition counts months of ordinary history as
-- today's leftovers, which is how the first run of this read nine clean orders as nine faults.
\echo '== a vehicle standing on a node it does not hold, while holding others (none expected)'
SELECT v.vehicle_id,v.node_id,
       (SELECT string_agg(r.node_id::text,',' ORDER BY r.sequence) FROM traffic.node_reservation r
         WHERE r.site_id=v.site_id AND r.vehicle_id=v.vehicle_id AND r.released_at IS NULL) AS holds
 FROM fleet.vehicle_state v
 WHERE v.site_id=:site AND v.node_id IS NOT NULL AND v.x_mm IS NULL
   AND EXISTS (SELECT 1 FROM traffic.node_reservation r
                WHERE r.site_id=v.site_id AND r.vehicle_id=v.vehicle_id AND r.released_at IS NULL)
   AND NOT EXISTS (SELECT 1 FROM traffic.node_reservation r
                    WHERE r.site_id=v.site_id AND r.vehicle_id=v.vehicle_id AND r.released_at IS NULL
                      AND r.node_id=v.node_id);

-- The node a vehicle is standing on does not count, whatever became of the work: it is standing
-- there, and road under a standing vehicle is held for the plainest of reasons.
\echo '== road still held for work that has finished, under nobody (none expected)'
SELECT r.vehicle_id,r.order_id,o.status,count(*) AS nodes,min(r.granted_at) AS held_since
 FROM traffic.node_reservation r
 JOIN transport.order o ON o.id=r.order_id
 WHERE r.site_id=:site AND r.released_at IS NULL
   AND o.status IN ('COMPLETED','CANCELLED','FAILED')
   AND r.granted_at >= :'since'
   AND r.node_id <> COALESCE((SELECT v.node_id FROM fleet.vehicle_state v
                               WHERE v.site_id=r.site_id AND v.vehicle_id=r.vehicle_id),-1)
 GROUP BY r.vehicle_id,r.order_id,o.status;

\echo '== a vehicle still assigned to work that has finished (none expected)'
SELECT a.vehicle_id,a.order_id,o.status,a.assigned_at
 FROM transport.assignment a
 JOIN transport.order o ON o.id=a.order_id
 WHERE a.released_at IS NULL AND o.site_id=:site
   AND o.status IN ('COMPLETED','CANCELLED','FAILED')
   AND a.assigned_at >= :'since';

\echo '== a command claimed and never settled (none expected)'
SELECT command_id,vehicle_id,status,attempt,last_outcome,created_at
 FROM integration.command_outbox
 WHERE site_id=:site AND status='DISPATCHING' AND created_at >= :'since'
   AND created_at < clock_timestamp()-make_interval(mins=>:quiet_minutes);

-- QUEUED is not here: waiting is what a queue is for, and under a saturated load most of the work
-- is waiting by design. A long leg can also outlast this quietly and legitimately -- on the sample
-- layout the slowest tenth of orders take over ten minutes -- so this one is a thing to look at
-- rather than a thing that must be empty.
\echo '== work in motion with no transition for a while (worth a look, not necessarily wrong)'
SELECT o.id,o.status,o.vehicle_type,o.created_at,
       (SELECT max(e.occurred_at) FROM transport.order_event e WHERE e.order_id=o.id) AS last_event
 FROM transport.order o
 WHERE o.site_id=:site
   AND o.status NOT IN ('QUEUED','COMPLETED','CANCELLED','FAILED','RECOVERY_REQUIRED')
   AND o.created_at >= :'since'
   AND COALESCE((SELECT max(e.occurred_at) FROM transport.order_event e WHERE e.order_id=o.id),o.created_at)
       < clock_timestamp()-make_interval(mins=>:quiet_minutes)
 ORDER BY o.id;

\echo '== a vehicle waiting on road for longer than it should (none expected)'
SELECT w.vehicle_id,w.wanted_node_id,w.blocked_by_vehicle_id,w.since
 FROM traffic.reservation_wait w
 WHERE w.site_id=:site AND w.since < clock_timestamp()-make_interval(mins=>:quiet_minutes);

\echo '== how the run ended, by outcome (not an invariant: the shape of the work)'
SELECT status,count(*) AS orders,
       round(avg(EXTRACT(EPOCH FROM terminal_at-created_at))::numeric,1) AS mean_seconds
 FROM transport.order
 WHERE site_id=:site AND request_source='SIM_LOAD' AND created_at >= :'since'
 GROUP BY status ORDER BY status;
