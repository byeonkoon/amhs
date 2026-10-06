-- Whether a site may progress is answered in one place: it is running, and it is not waiting for the
-- comparison that follows a server start. The open check is mirrored here so every path that
-- grants road or sends a command -- not only the execution pass -- reads the same answer.
ALTER TABLE platform.site_operation ADD COLUMN IF NOT EXISTS restart_check_since TIMESTAMPTZ;
UPDATE platform.site_operation o SET restart_check_since=c.started_at
 FROM transport.restart_check c WHERE c.site_id=o.site_id AND c.finished_at IS NULL;

-- A vehicle's grant numbers only ever go up, released grants included, so a road granted again after
-- road was given back never takes the number (and the command id) of the grant before it.
CREATE INDEX IF NOT EXISTS ix_reservation_vehicle_all ON traffic.node_reservation(site_id,vehicle_id,sequence);
