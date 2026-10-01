-- Whether the site runs on real devices or on vehicles simulated inside the server. In SIMULATION
-- every movement and transfer is carried out by simulated vehicles; in OPERATION commands go to
-- device adapters and nothing is simulated. Changing it needs maintenance, like every option.
ALTER TABLE platform.site_operation ADD COLUMN IF NOT EXISTS run_mode VARCHAR(12) NOT NULL DEFAULT 'OPERATION';
ALTER TABLE platform.site_operation ADD CONSTRAINT ck_site_run_mode CHECK(run_mode IN ('SIMULATION','OPERATION'));
