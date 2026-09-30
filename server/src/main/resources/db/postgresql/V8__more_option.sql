-- Movement-authority extension (MORE) settings, part of the site operation option.
-- NODE issues MORE when at most more_remaining_nodes authorized nodes remain.
-- DISTANCE issues MORE when the remaining authorized distance is at most
-- more_remaining_percent of the distance authorized when the movement started.
ALTER TABLE platform.site_operation
 ADD COLUMN more_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN more_type VARCHAR(8) NOT NULL DEFAULT 'NODE' CHECK(more_type IN ('NODE','DISTANCE')),
 ADD COLUMN more_remaining_nodes INT NOT NULL DEFAULT 3 CHECK(more_remaining_nodes BETWEEN 1 AND 99),
 ADD COLUMN more_remaining_percent INT NOT NULL DEFAULT 25 CHECK(more_remaining_percent BETWEEN 1 AND 99),
 ADD CONSTRAINT ck_more_nodes_below_node_count CHECK(more_remaining_nodes<node_count);
