-- A vehicle family's dialect, as the site installed it. Until now a mapping document lived in a
-- directory on the server's disk, which is the one piece of this system's configuration that could
-- not be changed from a screen, could not be versioned and left no record of who changed it. The
-- documents shipped with the server stay where they are; this is where a site puts its own, and the
-- code is what a protocol profile names.
CREATE TABLE IF NOT EXISTS integration.message_mapping(
 id BIGINT NOT NULL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 code VARCHAR(64) COLLATE "C" NOT NULL,
 adapter VARCHAR(32) NOT NULL,
 notes VARCHAR(512),
 document TEXT NOT NULL,
 updated_by VARCHAR(128),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_message_mapping_code UNIQUE(site_id,code));
