-- A charge owns its vehicle from the moment it is told to start until it is seen to have stopped,
-- so work is not given to a vehicle that is about to charge or still charging.
CREATE TABLE IF NOT EXISTS transport.charge_session(
 site_id BIGINT NOT NULL,
 vehicle_id BIGINT NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 stop_requested_at TIMESTAMPTZ,
 PRIMARY KEY(site_id,vehicle_id));

-- When the vehicle last became idle on a node; null while it is not. Reports that keep it idle keep
-- the time, so "idle for a while" is measured from when it stopped, not from its latest report.
ALTER TABLE fleet.vehicle_state ADD COLUMN IF NOT EXISTS idle_since TIMESTAMPTZ;
UPDATE fleet.vehicle_state SET idle_since=reported_at WHERE idle AND x_mm IS NULL AND idle_since IS NULL;
