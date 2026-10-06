-- A transfer report that arrives after its order has ended and says the carrier is somewhere the
-- order does not record. It is not dropped -- a carrier that moved after its work was closed is a
-- carrier nobody is tracking -- and it is not allowed to reopen the order either. It waits here,
-- shown as an alarm, until a person has dealt with it and said how.
CREATE TABLE IF NOT EXISTS transport.late_evidence(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 order_id BIGINT NOT NULL REFERENCES transport.order(id),
 order_status VARCHAR(24) NOT NULL,
 reported_status VARCHAR(24) NOT NULL,
 recorded_location VARCHAR(16),
 reported_location VARCHAR(16) NOT NULL,
 evidence_kind VARCHAR(32) NOT NULL,
 command_id VARCHAR(128),
 received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 resolved_at TIMESTAMPTZ,
 resolved_by VARCHAR(128),
 resolution VARCHAR(256));
CREATE INDEX IF NOT EXISTS late_evidence_open ON transport.late_evidence(site_id,received_at) WHERE resolved_at IS NULL;
