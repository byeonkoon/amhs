-- Every transfer report a device made about an order, as it was judged. A report naming its
-- command is handled once: the same command saying the same thing again changes nothing, and a
-- command keeps the action (pickup or drop) it was first reported for, so a pickup heard again
-- while unloading is not taken for a failed drop.
CREATE TABLE IF NOT EXISTS transport.transfer_report(
 id BIGSERIAL PRIMARY KEY,
 order_id BIGINT NOT NULL REFERENCES transport.order(id),
 command_id VARCHAR(128),
 action VARCHAR(8) CHECK(action IN ('PICKUP','DROP')),
 reported_status VARCHAR(24) NOT NULL,
 carrier_location VARCHAR(16),
 outcome VARCHAR(24) NOT NULL,
 report_key VARCHAR(200),
 received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp());
CREATE UNIQUE INDEX IF NOT EXISTS transfer_report_once ON transport.transfer_report(order_id,report_key) WHERE report_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS transfer_report_command ON transport.transfer_report(order_id,command_id);

-- Late evidence also covers reports that contradict each other on an unfinished order, where which
-- came first cannot be told: the carrier's place is then not known, not whichever arrived last.
ALTER TABLE transport.late_evidence ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'AFTER_END';
