-- What a transition rests on. Finishing a pickup or a drop needs evidence of where the carrier now
-- is, and that evidence is kept with the event so the order's history says why it moved on.
ALTER TABLE transport.order_event
 ADD COLUMN IF NOT EXISTS evidence_kind VARCHAR(32),
 ADD COLUMN IF NOT EXISTS command_id VARCHAR(64),
 ADD COLUMN IF NOT EXISTS carrier_location VARCHAR(16);
ALTER TABLE transport.order_event ADD CONSTRAINT ck_order_event_carrier_location
 CHECK(carrier_location IS NULL OR carrier_location IN ('SOURCE','VEHICLE','DESTINATION','UNKNOWN'));

-- A pickup or drop that provably did not happen is tried again, up to a site limit. The count is
-- per order; beyond the limit the order goes to recovery instead of trying forever.
ALTER TABLE transport.order ADD COLUMN IF NOT EXISTS transfer_attempt INT NOT NULL DEFAULT 0;
ALTER TABLE platform.site_operation ADD COLUMN IF NOT EXISTS transfer_max_attempts INT NOT NULL DEFAULT 3;
ALTER TABLE platform.site_operation ADD CONSTRAINT ck_transfer_max_attempts
 CHECK(transfer_max_attempts BETWEEN 1 AND 10);
