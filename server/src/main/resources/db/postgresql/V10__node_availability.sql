ALTER TABLE topology.node ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN topology.edge.cost IS 'Configured travel time in seconds; not entry permission';
