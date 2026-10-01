-- Stop points, merge points and zones with a capacity, plus the options that govern MORE near
-- them. Each one changes what traffic grants; none of them is a label for display only.

-- A stop node ends every movement authority that reaches it: the vehicle halts there and is
-- given the next run only after it has stopped. MORE never carries a vehicle through it.
-- A merge point is where two tracks join. The controller decides who enters it, first come first
-- served, unless the site hands that decision to the vehicles (merge_control below).
ALTER TABLE topology.node
 ADD COLUMN stop_node BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN merge_point BOOLEAN NOT NULL DEFAULT FALSE;

-- A zone is a set of nodes, typically a bay branching off the main loop, that holds at most
-- `capacity` vehicles. Entry is refused once it is full; leaving is never refused.
CREATE TABLE topology.zone(
 id BIGINT NOT NULL PRIMARY KEY,
 site_id BIGINT NOT NULL,
 revision_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 capacity INT NOT NULL CHECK(capacity BETWEEN 1 AND 100),
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_zone_code UNIQUE(site_id,revision_id,code),
 CONSTRAINT uq_zone_scope UNIQUE(site_id,revision_id,id),
 CONSTRAINT fk_zone_revision FOREIGN KEY(site_id,revision_id) REFERENCES topology.map_revision(site_id,id));
CREATE TABLE topology.zone_node(
 zone_id BIGINT NOT NULL,
 site_id BIGINT NOT NULL,
 revision_id BIGINT NOT NULL,
 node_id BIGINT NOT NULL,
 PRIMARY KEY(zone_id,node_id),
 -- A node counted in two zones would make one vehicle occupy two capacities at once.
 CONSTRAINT uq_zone_node UNIQUE(node_id),
 CONSTRAINT fk_zone_node_zone FOREIGN KEY(site_id,revision_id,zone_id) REFERENCES topology.zone(site_id,revision_id,id) ON DELETE CASCADE,
 CONSTRAINT fk_zone_node_node FOREIGN KEY(site_id,revision_id,node_id) REFERENCES topology.node(site_id,revision_id,id));

-- MORE limits and merge control. The lower bound stops a renewal the vehicle can no longer act
-- on; the vehicle signal makes MORE wait for the vehicle to say it can take a new destination.
ALTER TABLE platform.site_operation
 ADD COLUMN more_min_remaining_nodes INT NOT NULL DEFAULT 1,
 ADD COLUMN more_vehicle_signal BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN merge_control VARCHAR(16) NOT NULL DEFAULT 'CONTROLLER';
ALTER TABLE platform.site_operation
 ADD CONSTRAINT ck_more_min_remaining_nodes CHECK(more_min_remaining_nodes BETWEEN 0 AND 99
  AND more_min_remaining_nodes<=more_remaining_nodes),
 ADD CONSTRAINT ck_merge_control CHECK(merge_control IN ('CONTROLLER','VEHICLE_SIGNAL'));

-- Whether the vehicle says it can accept a changed destination right now. NULL means it has not
-- said, which is not the same as yes.
ALTER TABLE fleet.vehicle_state ADD COLUMN destination_change_enabled BOOLEAN;

-- `since` must survive repeated refusals for the same node, otherwise "who asked first" at a
-- merge point would be whoever was refused most recently. `seen_at` says the wait is still live.
ALTER TABLE traffic.reservation_wait ADD COLUMN seen_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Where along its route the vehicle is. A route that circles back past the same node cannot be
-- located by the first occurrence of that node, so progress is kept as an index.
ALTER TABLE transport.assignment ADD COLUMN route_cursor INT NOT NULL DEFAULT 0;

-- ---- Simulation layout: an OHT bay off the ring ----
-- The bay leaves the ring at OHT-N8 and rejoins at OHT-N10, so OHT-N10 is a merge point. A
-- vehicle refused entry to the full bay stays on the ring and comes round again.
INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES
 (121,1,1,'OHT-B1',21500,4000,TRUE),(122,1,1,'OHT-B2',23000,6000,TRUE),(123,1,1,'OHT-B3',21500,8000,TRUE);
INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled) VALUES
 (40,1,1,108,121,2500,TRUE),(41,1,1,121,122,2500,TRUE),(42,1,1,122,123,2500,TRUE),(43,1,1,123,110,2500,TRUE);
INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,linked_node_id) VALUES
 (8,1,1,'OHT-E','LOAD','OHT',122);
UPDATE topology.node SET merge_point=TRUE WHERE site_id=1 AND revision_id=1 AND id IN (110,207);
INSERT INTO topology.zone(id,site_id,revision_id,code,capacity,enabled) VALUES (1,1,1,'OHT-BAY-1',1,TRUE);
INSERT INTO topology.zone_node(zone_id,site_id,revision_id,node_id) VALUES (1,1,1,121),(1,1,1,122),(1,1,1,123);
