-- A notice must be claimed before it is said, not merely selected.
--
-- The sweep read every PENDING notice and settled it after the HTTP call, so two sweeps that
-- overlapped both read the same row and the system upstairs was told the same thing twice. Within
-- one instance a flag kept the ticks apart, but the flag is not the thing that matters: the test
-- that steps the sweep by hand hit this for real, and two instances of this application would hit
-- it in a plant.
--
-- This is the device outbox's answer, for the same reason it has that shape. DISPATCHING there is
-- SENDING here: claimed, not yet answered for. A row in it is one some sender is working on, and
-- nobody else picks it up.
ALTER TABLE integration.host_notice DROP CONSTRAINT IF EXISTS host_notice_state_check;
ALTER TABLE integration.host_notice ADD CONSTRAINT host_notice_state_check
 CHECK(state IN ('PENDING','SENDING','SENT','REFUSED','ABANDONED'));

-- When the claim was taken. A sender that died mid-attempt leaves a claim nobody is working on,
-- and this is how long ago that was: past the stale window the notice goes back to PENDING and is
-- tried again, carrying the same Idempotency-Key -- which is precisely what that key is for.
ALTER TABLE integration.host_notice
 ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

-- The claims in flight, for finding the stale ones. Few rows at any moment, which is the point.
CREATE INDEX IF NOT EXISTS ix_host_notice_claimed
 ON integration.host_notice(claimed_at) WHERE state='SENDING';
