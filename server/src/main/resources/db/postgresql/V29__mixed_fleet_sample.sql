-- A second vehicle of each kind, so the sample site runs OHT and AGV side by side on their own
-- tracks with the same transport, dispatch and traffic engine. Synthetic, like the rest of the map.
INSERT INTO fleet.vehicle_profile(id,site_id,revision_id,code,v_type,initial_node_id,enabled,assignment_enabled,name,speed_mm_s) VALUES
 (3,1,1,'OHT-02','OHT',112,TRUE,FALSE,'OHT 2',2000),
 (4,1,1,'AGV-02','AGV',210,TRUE,FALSE,'AGV 2',1000)
ON CONFLICT DO NOTHING;
