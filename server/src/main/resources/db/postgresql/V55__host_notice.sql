-- Telling the system that asked for the work how it ended.
--
-- Orders come in over REST with an Idempotency-Key, and until now nothing went back out: the system
-- that placed an order had to keep asking whether it was done. For work that ends in a minute that
-- is merely wasteful; for work that stops and waits for a person it means nobody upstairs is told,
-- and the order sits there looking slow rather than stuck.
--
-- The shape is the device outbox's, for the same reason it has that shape: the row is written in
-- the transaction that changed the order, so a rolled back change cannot leave a notice behind and
-- a committed one cannot lose it.

-- Where this site's notices go. One endpoint per site: more than one would be a routing rule, and
-- nothing here knows enough to route.
CREATE TABLE IF NOT EXISTS integration.host_endpoint(
 id BIGINT NOT NULL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 url VARCHAR(512) NOT NULL,
 -- A header to authenticate with, by name and value. The value is a secret: it is written here and
 -- never read back out, the same way an account password is.
 auth_header VARCHAR(128),
 auth_value VARCHAR(512),
 timeout_ms INT NOT NULL DEFAULT 3000 CHECK(timeout_ms BETWEEN 200 AND 30000),
 max_attempts INT NOT NULL DEFAULT 8 CHECK(max_attempts BETWEEN 1 AND 50),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_host_endpoint_site UNIQUE(site_id));

-- What is waiting to be said, and what became of saying it.
--
-- `state` is the same three-valued problem a device command has, and is answered the same way.
-- SENT is the far side having answered 2xx. REFUSED is it having answered 4xx -- an answer, and
-- not one that changes if it is repeated, so it is kept and not retried. ABANDONED is having run
-- out of attempts without ever getting an answer: nobody can say whether it arrived, which is why
-- it is a state of its own rather than a failure.
CREATE TABLE IF NOT EXISTS integration.host_notice(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 order_id BIGINT NOT NULL,
 -- Sent as the Idempotency-Key, so a notice the far side has already taken can be recognised when
 -- a retry repeats it. The same courtesy this controller asks for when orders come in.
 notice_key VARCHAR(128) COLLATE "C" NOT NULL,
 order_status VARCHAR(24) NOT NULL,
 body TEXT NOT NULL,
 state VARCHAR(12) NOT NULL DEFAULT 'PENDING'
  CHECK(state IN ('PENDING','SENT','REFUSED','ABANDONED')),
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(256),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 settled_at TIMESTAMPTZ,
 CONSTRAINT uq_host_notice_key UNIQUE(notice_key));

-- The sweep asks for this site's due work, oldest first.
CREATE INDEX IF NOT EXISTS ix_host_notice_due
 ON integration.host_notice(site_id,next_attempt_at) WHERE state='PENDING';
