-- An operator's way out of a deadlock: one vehicle in the chain gives back the road it holds ahead
-- of where it stands, once it is seen to have stopped, and asks for nothing more until the vehicle
-- it gave way to has taken the node it was waiting for. One open recovery per vehicle.
CREATE TABLE IF NOT EXISTS traffic.deadlock_recovery(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 vehicle_id BIGINT NOT NULL,
 beneficiary_vehicle_id BIGINT NOT NULL,
 wanted_node_id BIGINT NOT NULL,
 chain_vehicle_ids BIGINT[] NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('STOPPING','YIELDING','DONE','ABANDONED')),
 reason VARCHAR(256) NOT NULL,
 requested_by VARCHAR(128) NOT NULL,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 stop_command_id VARCHAR(128),
 stop_settled_at TIMESTAMPTZ,
 released_node_ids BIGINT[],
 released_at TIMESTAMPTZ,
 finished_at TIMESTAMPTZ,
 finish_reason VARCHAR(64));
CREATE UNIQUE INDEX IF NOT EXISTS deadlock_recovery_open
 ON traffic.deadlock_recovery(site_id,vehicle_id) WHERE finished_at IS NULL;
CREATE INDEX IF NOT EXISTS deadlock_recovery_recent ON traffic.deadlock_recovery(site_id,requested_at DESC);
