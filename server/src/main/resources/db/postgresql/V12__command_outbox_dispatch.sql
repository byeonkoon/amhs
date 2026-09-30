-- A send whose outcome is unknown must never be silently repeated: a movement command the
-- device may already be executing would be issued twice. DISPATCHING is that unknown state,
-- claimed before the send and resolved after it, so a crash mid-send is visible instead of
-- being replayed. Only PENDING and FAILED, where nothing reached the device, are retried.
ALTER TABLE integration.command_outbox DROP CONSTRAINT IF EXISTS command_outbox_status_check;
ALTER TABLE integration.command_outbox ADD CONSTRAINT command_outbox_status_check
 CHECK(status IN ('PENDING','DISPATCHING','SENT','ACCEPTED','FAILED'));
ALTER TABLE integration.command_outbox
 ADD COLUMN IF NOT EXISTS available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 ADD COLUMN IF NOT EXISTS last_outcome VARCHAR(32),
 ADD COLUMN IF NOT EXISTS last_error VARCHAR(256),
 ADD COLUMN IF NOT EXISTS settled_at TIMESTAMPTZ;
DROP INDEX IF EXISTS integration.ix_outbox_pending;
CREATE INDEX ix_outbox_claimable ON integration.command_outbox(available_at,created_at,id)
 WHERE status IN ('PENDING','FAILED');
CREATE INDEX ix_outbox_unsettled ON integration.command_outbox(status,created_at)
 WHERE status IN ('DISPATCHING','SENT');
