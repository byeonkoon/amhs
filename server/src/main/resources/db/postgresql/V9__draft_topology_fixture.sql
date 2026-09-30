-- Synthetic draft: preserve the existing active map and its vehicle configuration.
INSERT INTO topology.map_revision(id,site_id,revision,status) VALUES(2,1,2,'DRAFT');
INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm) VALUES
(1101,1,2,'OHT-N1',1000,1000),(1102,1,2,'OHT-N2',4000,1000),
(1103,1,2,'OHT-N3',7000,1000),(1104,1,2,'OHT-N4',10000,1000),
(1105,1,2,'OHT-N5',4000,2500),(1106,1,2,'OHT-N6',10000,2500),
(1201,1,2,'AGV-N1',1000,5000),(1202,1,2,'AGV-N2',4000,5000),
(1203,1,2,'AGV-N3',7000,5000),(1204,1,2,'AGV-N4',10000,5000),
(1205,1,2,'AGV-N5',4000,6500),(1206,1,2,'AGV-N6',10000,6500);
-- N1 -> N4 is one-way. N5 has a blocked incoming edge; N6 is disconnected.
INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled) VALUES
(1101,1,2,1101,1102,3000,TRUE),(1102,1,2,1102,1103,3000,TRUE),
(1103,1,2,1103,1104,3000,TRUE),(1104,1,2,1102,1105,1500,FALSE),
(1201,1,2,1201,1202,3000,TRUE),(1202,1,2,1202,1203,3000,TRUE),
(1203,1,2,1203,1204,3000,TRUE),(1204,1,2,1202,1205,1500,FALSE);
INSERT INTO topology.port(id,site_id,revision_id,code,port_type,maker_type,access_vehicle_type,linked_node_id) VALUES
(1101,1,2,'OHT-A','LOAD','SYNTHETIC','OHT',1101),
(1102,1,2,'OHT-B','UNLOAD','SYNTHETIC','OHT',1104),
(1201,1,2,'AGV-A','LOAD','SYNTHETIC','AGV',1201),
(1202,1,2,'AGV-B','UNLOAD','SYNTHETIC','AGV',1204);
INSERT INTO fleet.vehicle_profile(id,site_id,revision_id,code,v_type,initial_node_id,enabled,assignment_enabled,name) VALUES
(1101,1,2,'OHT-DRAFT-01','OHT',1101,TRUE,FALSE,'OHT draft fixture'),
(1201,1,2,'AGV-DRAFT-01','AGV',1201,TRUE,FALSE,'AGV draft fixture');
