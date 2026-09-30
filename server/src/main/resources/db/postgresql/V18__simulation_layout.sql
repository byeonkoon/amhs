-- A layout worth watching. The previous fixture was a short two-way stub: every route was the
-- same three nodes, congestion could not happen anywhere except head on, and nothing had to be
-- chosen between. This gives each network the shape its traffic problem actually has.
--
-- OHT runs a one-way ring of twenty nodes. A closed loop is how overhead track is normally laid,
-- and being one-way means two vehicles never meet nose to nose: they queue instead, which is the
-- congestion the reservation logic is for.
--
-- AGV runs a loop that splits and rejoins, so a route has a real choice to make and two vehicles
-- can pass the same span by different branches.

-- ---- OHT ring: 101..107 already exist along the top; the rest closes the loop ----
INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES
 (106,1,1,'OHT-N6',16000,1000,TRUE),(107,1,1,'OHT-N7',19000,1000,TRUE),
 (108,1,1,'OHT-N8',19000,4000,TRUE),(109,1,1,'OHT-N9',19000,6000,TRUE),
 (110,1,1,'OHT-N10',19000,8000,TRUE),(111,1,1,'OHT-N11',19000,10000,TRUE),
 (112,1,1,'OHT-N12',16000,10000,TRUE),(113,1,1,'OHT-N13',13000,10000,TRUE),
 (114,1,1,'OHT-N14',10000,10000,TRUE),(115,1,1,'OHT-N15',7000,10000,TRUE),
 (116,1,1,'OHT-N16',4000,10000,TRUE),(117,1,1,'OHT-N17',1000,10000,TRUE),
 (118,1,1,'OHT-N18',1000,8000,TRUE),(119,1,1,'OHT-N19',1000,6000,TRUE),
 (120,1,1,'OHT-N20',1000,4000,TRUE);

-- The ring is one-way, so the reverse edges of the old two-way stub are removed rather than
-- disabled: a one-way rail does not have a reverse segment that happens to be switched off.
DELETE FROM topology.edge WHERE id IN (3,4,10,12);

INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled) VALUES
 (13,1,1,105,106,3000,TRUE),(14,1,1,106,107,3000,TRUE),
 (15,1,1,107,108,3000,TRUE),(16,1,1,108,109,2000,TRUE),(17,1,1,109,110,2000,TRUE),
 (18,1,1,110,111,2000,TRUE),
 (19,1,1,111,112,3000,TRUE),(20,1,1,112,113,3000,TRUE),(21,1,1,113,114,3000,TRUE),
 (22,1,1,114,115,3000,TRUE),(23,1,1,115,116,3000,TRUE),(24,1,1,116,117,3000,TRUE),
 (25,1,1,117,118,2000,TRUE),(26,1,1,118,119,2000,TRUE),(27,1,1,119,120,2000,TRUE),
 (28,1,1,120,101,3000,TRUE);

-- ---- AGV loop with a split and a merge, inside the ring ----
UPDATE topology.node SET x_mm=4000,y_mm=4000 WHERE id=201;
UPDATE topology.node SET x_mm=7000,y_mm=4000 WHERE id=202;
UPDATE topology.node SET x_mm=10000,y_mm=3000 WHERE id=203;
INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled) VALUES
 (204,1,1,'AGV-N4',13000,3000,TRUE),
 (205,1,1,'AGV-N5',10000,5500,TRUE),(206,1,1,'AGV-N6',13000,5500,TRUE),
 (207,1,1,'AGV-N7',16000,4000,TRUE),(208,1,1,'AGV-N8',16000,7000,TRUE),
 (209,1,1,'AGV-N9',13000,7000,TRUE),(210,1,1,'AGV-N10',10000,7000,TRUE),
 (211,1,1,'AGV-N11',7000,7000,TRUE),(212,1,1,'AGV-N12',4000,7000,TRUE);

DELETE FROM topology.edge WHERE id IN (7,8);

INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled) VALUES
 -- upper branch
 (29,1,1,203,204,3000,TRUE),(30,1,1,204,207,3100,TRUE),
 -- lower branch, longer but separate, so two vehicles can pass the same span at once
 (31,1,1,202,205,3400,TRUE),(32,1,1,205,206,3000,TRUE),(33,1,1,206,207,3400,TRUE),
 -- return leg
 (34,1,1,207,208,3000,TRUE),(35,1,1,208,209,3000,TRUE),(36,1,1,209,210,3000,TRUE),
 (37,1,1,210,211,3000,TRUE),(38,1,1,211,212,3000,TRUE),(39,1,1,212,201,3000,TRUE);

-- A second port on each network so a route can be asked for across the long way round.
INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,linked_node_id) VALUES
 (6,1,1,'OHT-D','LOAD','OHT',114),
 (7,1,1,'AGV-C','UNLOAD','AGV',209);
