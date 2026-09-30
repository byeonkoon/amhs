-- A fourth authority level. Configuring vehicles and equipment is a different job from changing
-- the track the vehicles run on, so the two stop sharing one level.
ALTER TABLE platform.account DROP CONSTRAINT IF EXISTS account_role_check;
ALTER TABLE platform.account ADD CONSTRAINT account_role_check
 CHECK(role IN ('VIEWER','OPERATOR','ENGINEER','ADMIN'));

-- Site-wide options are not one machine's parameters, so they live together and apart from the
-- equipment screens. Every option here changes behaviour that is already implemented; an option
-- nothing reads would only look like a setting.
ALTER TABLE platform.site_operation
 ADD COLUMN IF NOT EXISTS auto_dispatch BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN IF NOT EXISTS command_max_attempts INT NOT NULL DEFAULT 5,
 ADD COLUMN IF NOT EXISTS command_retry_seconds INT NOT NULL DEFAULT 15,
 ADD COLUMN IF NOT EXISTS position_stale_seconds INT NOT NULL DEFAULT 10;
ALTER TABLE platform.site_operation
 ADD CONSTRAINT ck_command_max_attempts CHECK(command_max_attempts BETWEEN 1 AND 20),
 ADD CONSTRAINT ck_command_retry_seconds CHECK(command_retry_seconds BETWEEN 1 AND 3600),
 ADD CONSTRAINT ck_position_stale_seconds CHECK(position_stale_seconds BETWEEN 1 AND 3600);
