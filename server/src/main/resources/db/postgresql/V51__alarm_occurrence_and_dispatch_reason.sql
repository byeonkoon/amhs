-- Alarms stay conditions worked out from the site; this is the record of having seen them. The
-- upkeep notes when each was first seen true, last seen true and seen gone, with what anybody said
-- about it meanwhile and what it was about, so "what went wrong last night, and who looked" has an
-- answer after the condition itself has ended. Times are observed times, not reported ones.
CREATE TABLE IF NOT EXISTS platform.alarm_occurrence(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 alarm_key VARCHAR(160) NOT NULL,
 severity VARCHAR(8) NOT NULL,
 rule VARCHAR(64) NOT NULL,
 subject VARCHAR(256),
 summary VARCHAR(512),
 vehicle_ids BIGINT[],
 order_id BIGINT,
 command_id VARCHAR(128),
 condition_since TIMESTAMPTZ,
 first_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 cleared_at TIMESTAMPTZ,
 ack_reason VARCHAR(256),
 ack_actor VARCHAR(64),
 ack_at TIMESTAMPTZ);
CREATE UNIQUE INDEX IF NOT EXISTS alarm_occurrence_open ON platform.alarm_occurrence(site_id,alarm_key) WHERE cleared_at IS NULL;
CREATE INDEX IF NOT EXISTS alarm_occurrence_recent ON platform.alarm_occurrence(site_id,first_seen_at DESC);
CREATE INDEX IF NOT EXISTS alarm_occurrence_order ON platform.alarm_occurrence(order_id) WHERE order_id IS NOT NULL;

-- Why a waiting order was last passed over, and when: the answer the queue gave it, kept with it,
-- so "why is this one not moving" can be read off the order rather than guessed.
ALTER TABLE transport.order ADD COLUMN IF NOT EXISTS last_dispatch_reason VARCHAR(64);
ALTER TABLE transport.order ADD COLUMN IF NOT EXISTS last_dispatch_at TIMESTAMPTZ;
