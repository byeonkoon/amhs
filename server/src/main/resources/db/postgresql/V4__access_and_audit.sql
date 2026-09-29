-- A16 access control and operation audit.
CREATE TABLE platform.account(
 id BIGINT NOT NULL PRIMARY KEY,site_id BIGINT NOT NULL REFERENCES platform.site(id),
 code VARCHAR(64) NOT NULL,display_name VARCHAR(128) NOT NULL,
 role VARCHAR(16) NOT NULL CHECK(role IN ('VIEWER','OPERATOR','ADMIN')),
 token_sha256 CHAR(64),enabled BOOLEAN NOT NULL DEFAULT TRUE,
 CONSTRAINT uq_account_code UNIQUE(site_id,code),
 CONSTRAINT uq_account_token UNIQUE(token_sha256));
-- Operation audit. Records who did what to which target with which outcome.
-- Reads are not recorded; only state-changing requests are.
CREATE TABLE platform.audit_event(
 id BIGSERIAL PRIMARY KEY,site_id BIGINT,
 actor_account_id BIGINT REFERENCES platform.account(id),actor_code VARCHAR(64) NOT NULL,actor_role VARCHAR(16) NOT NULL,
 action VARCHAR(16) NOT NULL,target VARCHAR(512) NOT NULL,
 result VARCHAR(16) NOT NULL CHECK(result IN ('ALLOWED','DENIED','FAILED')),
 status_code INT NOT NULL,reason VARCHAR(512),trace_id VARCHAR(64) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX ix_audit_occurred ON platform.audit_event(occurred_at DESC);
CREATE INDEX ix_audit_actor ON platform.audit_event(actor_code,occurred_at DESC);
