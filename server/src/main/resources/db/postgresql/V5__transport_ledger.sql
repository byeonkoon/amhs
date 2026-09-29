-- A06 transport order ledger. Work state, its history and the outbound command record.
CREATE SCHEMA transport;
CREATE TABLE transport.order(
 id BIGSERIAL PRIMARY KEY,site_id BIGINT NOT NULL REFERENCES platform.site(id),revision_id BIGINT NOT NULL,
 request_source VARCHAR(64) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,
 -- Fingerprint of the accepted payload. Same key with a different fingerprint is a conflict.
 request_fingerprint CHAR(64) NOT NULL,
 source_port_id BIGINT NOT NULL,destination_port_id BIGINT NOT NULL,
 vehicle_type VARCHAR(8) NOT NULL CHECK(vehicle_type IN ('OHT','AGV')),
 carrier_code VARCHAR(64),priority INT NOT NULL CHECK(priority BETWEEN 0 AND 100),
 status VARCHAR(24) NOT NULL,
 -- Carrier position is allowed to be unknown; it is never guessed.
 carrier_location VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK(carrier_location IN ('SOURCE','VEHICLE','DESTINATION','UNKNOWN')),
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),started_at TIMESTAMPTZ,terminal_at TIMESTAMPTZ,
 cancel_reason VARCHAR(512),
 CONSTRAINT uq_order_idempotency UNIQUE(site_id,request_source,idempotency_key),
 CONSTRAINT ck_order_ports CHECK(source_port_id<>destination_port_id));
CREATE INDEX ix_order_status ON transport.order(site_id,status,created_at DESC);
-- One active assignment per vehicle and per order, enforced by filtered unique indexes.
CREATE TABLE transport.assignment(
 id BIGSERIAL PRIMARY KEY,order_id BIGINT NOT NULL REFERENCES transport.order(id),
 vehicle_id BIGINT NOT NULL,assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),released_at TIMESTAMPTZ);
CREATE UNIQUE INDEX uq_active_assignment_vehicle ON transport.assignment(vehicle_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_active_assignment_order ON transport.assignment(order_id) WHERE released_at IS NULL;
-- Append-only transition history. seq is per order.
CREATE TABLE transport.order_event(
 id BIGSERIAL PRIMARY KEY,order_id BIGINT NOT NULL REFERENCES transport.order(id),seq INT NOT NULL,
 old_status VARCHAR(24),new_status VARCHAR(24) NOT NULL,reason VARCHAR(512),
 actor_code VARCHAR(64),trace_id VARCHAR(64),occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT uq_order_event_seq UNIQUE(order_id,seq));
-- Outbound device commands written in the same transaction as the state change (A22 publishes them).
CREATE TABLE integration.command_outbox(
 id BIGSERIAL PRIMARY KEY,order_id BIGINT NOT NULL REFERENCES transport.order(id),
 command_id VARCHAR(64) NOT NULL UNIQUE,command_type VARCHAR(32) NOT NULL,
 payload TEXT NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','ACCEPTED','FAILED')),
 attempt INT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),published_at TIMESTAMPTZ);
CREATE INDEX ix_outbox_pending ON integration.command_outbox(status,created_at) WHERE status='PENDING';
