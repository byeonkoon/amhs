-- Commands are sent one vehicle at a time in the order they were written. A later command for a
-- vehicle waits while an earlier one has not left, has failed and is waiting to be tried again, or
-- left with an outcome nobody knows. The vehicle is a column rather than something read out of the
-- payload, so the ordering does not depend on what a payload happens to carry.
ALTER TABLE integration.command_outbox ADD COLUMN IF NOT EXISTS vehicle_id BIGINT;
UPDATE integration.command_outbox SET vehicle_id=(payload::jsonb->>'vehicleId')::BIGINT
 WHERE vehicle_id IS NULL AND payload LIKE '{%' AND (payload::jsonb->>'vehicleId') IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_outbox_vehicle_open ON integration.command_outbox(vehicle_id,id)
 WHERE status IN ('PENDING','DISPATCHING','FAILED');

-- A command that never left is withdrawn when its order stops moving (cancel, recovery, end). It
-- stays as a record of what was decided and then taken back, and it is never sent.
ALTER TABLE integration.command_outbox DROP CONSTRAINT IF EXISTS command_outbox_status_check;
ALTER TABLE integration.command_outbox ADD CONSTRAINT command_outbox_status_check
 CHECK(status IN ('PENDING','DISPATCHING','SENT','ACCEPTED','FAILED','WITHDRAWN'));

-- Where in the stored route the pickup is. Before the carrier is picked up a vehicle is authorised
-- only as far as this index; the delivery leg is granted after the pickup is reported. NULL on rows
-- written before this column existed, which keep the whole route.
ALTER TABLE transport.assignment ADD COLUMN IF NOT EXISTS pickup_index INT;
ALTER TABLE transport.assignment ADD CONSTRAINT ck_assignment_pickup_index
 CHECK(pickup_index IS NULL OR pickup_index>=0);
