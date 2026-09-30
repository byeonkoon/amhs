-- A route is a proposal; this is the permission to occupy. One vehicle per node, enforced by a
-- filtered unique index rather than by application order, so two requests racing for the same
-- node are settled by the database.
CREATE SCHEMA traffic;
CREATE TABLE traffic.node_reservation(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 revision_id BIGINT NOT NULL,
 node_id BIGINT NOT NULL,
 vehicle_id BIGINT NOT NULL,
 order_id BIGINT REFERENCES transport.order(id),
 -- Position within this vehicle's granted run. Releasing "everything behind" is a comparison
 -- on this, never on time: a node is freed by evidence of arrival further along, nothing else.
 sequence INT NOT NULL,
 granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 released_at TIMESTAMPTZ,
 release_reason VARCHAR(64));
CREATE UNIQUE INDEX uq_active_node_reservation ON traffic.node_reservation(node_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_active_vehicle_sequence ON traffic.node_reservation(vehicle_id,sequence) WHERE released_at IS NULL;
CREATE INDEX ix_reservation_vehicle ON traffic.node_reservation(site_id,vehicle_id,sequence) WHERE released_at IS NULL;

-- What a vehicle could not get, and who is standing in the way. Each vehicle waits for at most
-- one thing, so following blocked_by from vehicle to vehicle either ends or returns to the
-- start, which is the deadlock.
CREATE TABLE traffic.reservation_wait(
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 vehicle_id BIGINT NOT NULL,
 wanted_node_id BIGINT NOT NULL,
 blocked_by_vehicle_id BIGINT,
 since TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(site_id,vehicle_id));
