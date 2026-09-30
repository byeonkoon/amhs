-- Assignment needs the vehicle's last reported state, and that state must be able to say
-- "unknown". node_id stays NULL until something reports a position: a vehicle whose location
-- is unknown must fail routing with that reason rather than be placed at its configured node.
CREATE TABLE fleet.vehicle_state(
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 vehicle_id BIGINT NOT NULL,
 revision_id BIGINT NOT NULL,
 node_id BIGINT,
 online BOOLEAN NOT NULL DEFAULT FALSE,
 remote BOOLEAN NOT NULL DEFAULT FALSE,
 idle BOOLEAN NOT NULL DEFAULT FALSE,
 carrier_present BOOLEAN NOT NULL DEFAULT FALSE,
 -- Where the values came from. A configured position is not a report from the vehicle.
 source VARCHAR(24) NOT NULL CHECK(source IN ('INITIAL_CONFIGURATION','SIMULATED','DEVICE')),
 reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(site_id,vehicle_id));
-- Why an assignment ended, so a released vehicle can be told apart from a finished job.
ALTER TABLE transport.assignment ADD COLUMN IF NOT EXISTS release_reason VARCHAR(128);
CREATE INDEX ix_assignment_order ON transport.assignment(order_id,assigned_at DESC);
