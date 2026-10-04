-- A place for a vehicle to stand when it has nothing to do, named per kind of vehicle.
--
-- Until now a waiting place was a property of one vehicle: its home node. That answers "where does
-- this vehicle go" and not "where may a vehicle wait", so a site could not be laid out without
-- giving every vehicle its own spot, and a vehicle without one simply stood wherever it finished.
--
-- The kind is part of it rather than derived. OHTs run on rail and AGVs on the floor, so a place
-- that is a waiting spot for one is not reachable by the other; which network a node belongs to is
-- implicit in the track, and guessing it from the node's name is how a vehicle gets sent somewhere
-- it cannot go. One column rather than two flags: a node cannot be a waiting place for both kinds,
-- because the two kinds do not share track.
ALTER TABLE topology.node ADD COLUMN IF NOT EXISTS wait_for VARCHAR(8);
ALTER TABLE topology.node ADD CONSTRAINT ck_node_wait_for CHECK(wait_for IS NULL OR wait_for IN ('AGV','OHT'));

-- No waiting place is set on the existing sample map. Where vehicles wait is a decision about a
-- particular site's layout, and the first map that has somewhere sensible to put one says so for
-- itself. A charge point is never one of them: a vehicle standing there to wait is a charger
-- nobody else can use.
