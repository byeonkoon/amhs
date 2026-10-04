-- Somebody saw it. That is all this records.
--
-- Acknowledging is not fixing, and the two must never be the same button. An alarm here is a
-- condition worked out from the site, so it goes when the condition goes and not before; letting
-- somebody clear one that is still true is how a real fault leaves a screen. What was missing is
-- only the other half: whether anyone has looked yet, so that a room of people can tell a new fault
-- from one already being dealt with.
--
-- It lives in platform, with the other things that are about people rather than about the floor:
-- who may come in, what they did, and now what they have seen.
CREATE TABLE platform.alarm_acknowledgement(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 -- The alarm's own identity, as the rule that raised it builds it: the rule and what it is about.
 alarm_key VARCHAR(128) NOT NULL,
 -- What the person knows or is doing about it. Required: an acknowledgement that says only that
 -- somebody clicked tells the next reader nothing they did not already know.
 reason VARCHAR(256) NOT NULL,
 actor_code VARCHAR(64),
 acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 -- One per alarm. Seeing it twice is still seeing it once.
 CONSTRAINT uq_alarm_ack UNIQUE(site_id,alarm_key));

CREATE INDEX ix_alarm_ack_site ON platform.alarm_acknowledgement(site_id);
