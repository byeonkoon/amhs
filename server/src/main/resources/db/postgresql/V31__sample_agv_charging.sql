-- The sample AGVs charge at the nearest free charge point; each also names its own charge node.
UPDATE fleet.vehicle_profile SET charging_policy='NEAREST',charge_node_id=212 WHERE site_id=1 AND revision_id=1 AND code='AGV-01';
UPDATE fleet.vehicle_profile SET charging_policy='NEAREST',charge_node_id=205 WHERE site_id=1 AND revision_id=1 AND code='AGV-02';
