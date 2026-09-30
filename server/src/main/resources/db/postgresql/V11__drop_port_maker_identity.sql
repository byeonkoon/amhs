-- The port maker column carried equipment-vendor identity only: nothing in this system
-- reads or branches on it. Dropping it removes the one field that invited vendor names
-- into the schema. Vehicle communication keeps its own manufacturer field because the
-- VDA5050 topic path requires it.
ALTER TABLE topology.port DROP COLUMN IF EXISTS maker_type;
