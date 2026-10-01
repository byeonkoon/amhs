-- A vehicle that has finished its work inside a zone is moved out without a transport order, so a
-- command no longer needs an order. It belongs to the site, and to the vehicle it moves.
ALTER TABLE integration.command_outbox ADD COLUMN IF NOT EXISTS site_id BIGINT;
UPDATE integration.command_outbox c SET site_id=o.site_id
 FROM transport.order o WHERE o.id=c.order_id AND c.site_id IS NULL;
ALTER TABLE integration.command_outbox ALTER COLUMN site_id SET NOT NULL;
ALTER TABLE integration.command_outbox ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE integration.command_outbox ADD CONSTRAINT ck_outbox_order_or_vehicle
 CHECK(order_id IS NOT NULL OR vehicle_id IS NOT NULL);
CREATE INDEX IF NOT EXISTS ix_outbox_site ON integration.command_outbox(site_id,created_at,id);

-- One open move out of a zone per vehicle. The route is stored so the move can be continued past
-- congestion the way an order's route is, instead of being planned again from wherever it stopped.
CREATE TABLE transport.relocation(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL,
 revision_id BIGINT NOT NULL,
 vehicle_id BIGINT NOT NULL,
 zone_id BIGINT NOT NULL,
 route_node_ids BIGINT[] NOT NULL,
 target_node_id BIGINT NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ,
 finish_reason VARCHAR(32),
 CONSTRAINT fk_relocation_vehicle FOREIGN KEY(site_id,vehicle_id) REFERENCES fleet.vehicle_profile(site_id,id));
CREATE UNIQUE INDEX uq_relocation_open_vehicle ON transport.relocation(vehicle_id) WHERE finished_at IS NULL;
