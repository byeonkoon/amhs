-- A place off the main line to put a vehicle that should not be on it, and a way to say a vehicle
-- is not to be worked.
--
-- A zone until now meant one thing: an area that admits at most so many vehicles, which a vehicle
-- is moved out of once its work there is done. A maintenance area is the same shape and the
-- opposite intent -- a vehicle sent there is meant to stay. Without saying which kind it is, the
-- move-out rule would pull a vehicle straight back onto the line it was just taken off.
ALTER TABLE topology.zone ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'TRAFFIC';
ALTER TABLE topology.zone ADD CONSTRAINT ck_zone_kind CHECK(kind IN ('TRAFFIC','MAINTENANCE'));

-- Out of service is an operating state, not configuration. The moment it is most needed is while
-- the site is running -- a vehicle that cannot be fed has to come off the line now, not at the next
-- shutdown -- so it lives with the reported state and not with the vehicle's settings, which only
-- change in maintenance. It is also not the same as the configured "not for dispatch": that says
-- how a vehicle is normally used, this says something happened to this one.
ALTER TABLE fleet.vehicle_state
 ADD COLUMN IF NOT EXISTS out_of_service_since TIMESTAMPTZ,
 ADD COLUMN IF NOT EXISTS out_of_service_reason VARCHAR(256),
 ADD COLUMN IF NOT EXISTS out_of_service_by VARCHAR(64);

-- ---- Simulation layout: a maintenance siding off the OHT ring ----
-- It has to be off the running line to be worth anything. A vehicle that cannot be fed is going to
-- stop; stopping it on the ring stops everything behind it, because the ring is one way round. The
-- siding leaves at OHT-N12 and rejoins at OHT-N13, so a vehicle can be driven in, left, and driven
-- out again once it has been seen to -- a dead end on a one-way line could never be left.
INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES
 (124,1,1,'OHT-M1',16000,13000,TRUE),(125,1,1,'OHT-M2',13000,13000,TRUE);
INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled) VALUES
 (90,1,1,112,124,3000,TRUE),(91,1,1,124,125,3000,TRUE),(92,1,1,125,113,3000,TRUE);

-- Two bays, because one vehicle in for a look should not keep the next one out on the line.
INSERT INTO topology.zone(id,site_id,revision_id,code,capacity,enabled,kind)
 VALUES (2,1,1,'OHT-MAINT-1',2,TRUE,'MAINTENANCE');
INSERT INTO topology.zone_node(zone_id,site_id,revision_id,node_id) VALUES (2,1,1,124),(2,1,1,125);

-- Rejoining the ring is a merge like any other, so whoever asked first goes first.
UPDATE topology.node SET merge_point=TRUE WHERE site_id=1 AND revision_id=1 AND id=113;
