-- The route the assignment was costed against. Extending movement authority follows this rather
-- than replanning each time: a fresh plan could diverge from the nodes the vehicle already holds,
-- leaving reserved nodes nobody intends to use while asking for different ones.
ALTER TABLE transport.assignment ADD COLUMN IF NOT EXISTS route_node_ids BIGINT[] NOT NULL DEFAULT '{}';
