-- Moving an idle vehicle out of another one's way, sending idle vehicles home, and AGV charging.

-- How long a vehicle may wait for one standing idle in its way before that one is moved aside
-- (0: never), and how long a vehicle may stand without work before it is sent home (0: never).
ALTER TABLE platform.site_operation
 ADD COLUMN IF NOT EXISTS yield_after_seconds INT NOT NULL DEFAULT 5,
 ADD COLUMN IF NOT EXISTS home_return_seconds INT NOT NULL DEFAULT 0,
-- Charging thresholds, state of charge in percent: below request a vehicle asks to charge (work
-- still wins); below critical, without a carrier, it must charge and takes no work; above release
-- the request ends.
 ADD COLUMN IF NOT EXISTS charge_request_pct INT NOT NULL DEFAULT 40,
 ADD COLUMN IF NOT EXISTS charge_critical_pct INT NOT NULL DEFAULT 20,
 ADD COLUMN IF NOT EXISTS charge_release_pct INT NOT NULL DEFAULT 80;
ALTER TABLE platform.site_operation ADD CONSTRAINT ck_yield_home CHECK(yield_after_seconds BETWEEN 0 AND 600
 AND home_return_seconds BETWEEN 0 AND 3600);
ALTER TABLE platform.site_operation ADD CONSTRAINT ck_charge_levels CHECK(charge_critical_pct BETWEEN 1 AND 98
 AND charge_request_pct>charge_critical_pct AND charge_release_pct>charge_request_pct AND charge_release_pct<=100);

-- The new kinds of move without an order.
ALTER TABLE transport.relocation DROP CONSTRAINT IF EXISTS ck_relocation_purpose;
ALTER TABLE transport.relocation ADD CONSTRAINT ck_relocation_purpose
 CHECK(purpose IN ('ZONE_EVACUATION','MANUAL','YIELD','HOME','CHARGE'));

-- A node where an AGV can charge.
ALTER TABLE topology.node ADD COLUMN IF NOT EXISTS charge_point BOOLEAN NOT NULL DEFAULT FALSE;

-- State of charge as the vehicle reports it, percent; null when it does not report one.
ALTER TABLE fleet.vehicle_state ADD COLUMN IF NOT EXISTS battery_pct DOUBLE PRECISION;

-- A vehicle's charging request: 1 asks to charge (work still wins), 2 must charge.
CREATE TABLE IF NOT EXISTS transport.charge_request(
 site_id BIGINT NOT NULL,
 vehicle_id BIGINT NOT NULL,
 level SMALLINT NOT NULL CHECK(level IN (1,2)),
 since TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(site_id,vehicle_id));

-- Sample: two AGV charge points off the main AGV loop.
UPDATE topology.node SET charge_point=TRUE WHERE site_id=1 AND revision_id=1 AND id IN (205,212);
