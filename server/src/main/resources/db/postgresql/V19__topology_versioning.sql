-- Editing a node or a segment needs the same optimistic locking every other configuration has:
-- two people changing the track at once is exactly the case that must not silently merge.
ALTER TABLE topology.node ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE topology.edge ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
