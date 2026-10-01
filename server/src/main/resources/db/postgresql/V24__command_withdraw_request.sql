-- A command that was already being sent when its order stopped wanting it cannot be withdrawn on the
-- spot: the send is in progress and its outcome unknown. The request to withdraw is recorded on the
-- row instead, so a send that then proves not to have reached the device ends the command rather
-- than queueing it for another attempt.
ALTER TABLE integration.command_outbox ADD COLUMN IF NOT EXISTS withdraw_requested_at TIMESTAMPTZ;
