-- Getting in stops being a token somebody is handed and becomes a login somebody knows.
--
-- A long-lived token is a password that cannot be typed, cannot be changed by its owner, and is
-- read in full by whoever it passes through. A password is held by the person, and what is stored
-- is only a salted derivation of it, so the stored side cannot be turned back into a login.

-- The third level is the one that reads the site's work rather than configuring its machines, so
-- it is named for that.
UPDATE platform.account SET role='MANAGER' WHERE role='ENGINEER';
ALTER TABLE platform.account DROP CONSTRAINT IF EXISTS account_role_check;
ALTER TABLE platform.account ADD CONSTRAINT account_role_check
 CHECK(role IN ('VIEWER','OPERATOR','MANAGER','ADMIN'));

-- pbkdf2$<iterations>$<salt>$<derived>. Null until a password is set: an account that cannot be
-- logged into is a real state, and it is not the same as one with an empty password.
ALTER TABLE platform.account ADD COLUMN IF NOT EXISTS password_hash VARCHAR(256);
ALTER TABLE platform.account DROP COLUMN IF EXISTS token_sha256;

-- A login lasts a while and then stops. Only the hash of the session's own value is kept, for the
-- same reason the password is not kept: what is stored cannot be replayed as a login.
CREATE TABLE platform.user_session(
 id BIGSERIAL PRIMARY KEY,
 account_id BIGINT NOT NULL REFERENCES platform.account(id),
 token_sha256 CHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ NOT NULL,
 ended_at TIMESTAMPTZ,
 end_reason VARCHAR(32));
CREATE INDEX ix_session_account ON platform.user_session(account_id) WHERE ended_at IS NULL;
CREATE INDEX ix_session_expiry ON platform.user_session(expires_at) WHERE ended_at IS NULL;

-- Demo logins for the simulation, so the screens can be opened without setting anything up first.
-- Only the derivation of each password is here; the passwords themselves are not in this
-- repository. They are weak on purpose and meant to be read by anyone trying the demo: an
-- operating site changes every one of them before it carries real work.
INSERT INTO platform.account(id,site_id,code,display_name,role,enabled,password_hash) VALUES
 (8001,1,'Visitor','조회 전용','VIEWER',TRUE,
  'pbkdf2$210000$0lXLapUrVQp7_I8OmVsYBA$hu5-5CO-KqritykVXgny5Kwi7efSTmDKG9xPpL5PAyE'),
 (8002,1,'Operator','운전','OPERATOR',TRUE,
  'pbkdf2$210000$RRncuyQqR8s5Upj4rp3B8A$FUVuqwAgVGaKnVBqiuyJNuUnoMF-caXgpciPa-C1cYk'),
 (8003,1,'Manager','운영 관리','MANAGER',TRUE,
  'pbkdf2$210000$-dkAEy2kBWwVEvrFhD-rCg$NwM56C4jC5ODJrUfVw5MWGv4SZ-b4S9dH_f7YLM0jvE'),
 (8004,1,'Administrator','시스템 관리자','ADMIN',TRUE,
  'pbkdf2$210000$wTq-0NWIYGL1QOoVL4U6rQ$D2r7jCI6dOGKWeZXzrO0QENj8MIqJ199ofp2r1JyqxQ'),
 (8005,1,'developer','개발','ADMIN',TRUE,
  'pbkdf2$210000$J_NC5fkQcvJbW7-Ev0AaOw$7ZpSN0F_-mxqhHcELnj0F0SMXMey49zatCQ5ihyXaJs')
ON CONFLICT (id) DO NOTHING;
