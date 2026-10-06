-- After a server start, the ledger says what was in progress and nothing says what the vehicles did
-- while nobody was listening. Until the two have been compared the site does not progress: no
-- assignment, no new road, nothing sent. One check per start; it ends either because nothing was in
-- progress, or because an operator confirmed it once nothing unresolved was left.
CREATE TABLE IF NOT EXISTS transport.restart_check(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 finished_at TIMESTAMPTZ,
 outcome VARCHAR(32) CHECK(outcome IN ('NOTHING_IN_PROGRESS','CONFIRMED')),
 finished_by VARCHAR(128),
 finish_reason VARCHAR(256),
 released_nodes INT,
 withdrawn_commands INT,
 settled_commands INT NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX IF NOT EXISTS restart_check_open ON transport.restart_check(site_id) WHERE finished_at IS NULL;
CREATE INDEX IF NOT EXISTS restart_check_recent ON transport.restart_check(site_id,started_at DESC);
