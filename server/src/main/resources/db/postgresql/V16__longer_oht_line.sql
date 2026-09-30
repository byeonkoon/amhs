-- The active fixture's OHT line was three nodes long, shorter than a single movement authority,
-- so nothing on it could run out of permission partway. Two more nodes and a further port give a
-- route long enough to be granted in parts and extended while the vehicle is moving.
INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES
 (104,1,1,'OHT-N4',10000,1000,TRUE),(105,1,1,'OHT-N5',13000,1000,TRUE);
INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled) VALUES
 (9,1,1,103,104,3000,TRUE),(10,1,1,104,103,3000,TRUE),
 (11,1,1,104,105,3000,TRUE),(12,1,1,105,104,3000,TRUE);
INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,linked_node_id) VALUES
 (5,1,1,'OHT-C','UNLOAD','OHT',105);
