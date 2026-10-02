-- A move that is stopped early (its vehicle taken out of use) ends only on the vehicle's answer:
-- a report made after the stop was decided. This is when it was decided.
ALTER TABLE transport.relocation ADD COLUMN IF NOT EXISTS stop_requested_at TIMESTAMPTZ;

-- Travel speed per vehicle, used by the simulator to drive along a segment instead of jumping
-- node to node. New design values; a site sets its own.
ALTER TABLE fleet.vehicle_profile ADD COLUMN IF NOT EXISTS speed_mm_s INT NOT NULL DEFAULT 1000;
ALTER TABLE fleet.vehicle_profile ADD CONSTRAINT ck_vehicle_speed CHECK(speed_mm_s BETWEEN 50 AND 10000);
UPDATE fleet.vehicle_profile SET speed_mm_s=2000 WHERE v_type='OHT';

-- Where a vehicle is while it drives between nodes. Null while it stands on its node, which stays
-- the position traffic counts.
ALTER TABLE fleet.vehicle_state ADD COLUMN IF NOT EXISTS x_mm DOUBLE PRECISION, ADD COLUMN IF NOT EXISTS y_mm DOUBLE PRECISION;

-- A bent segment: the offset of its middle from the straight line, mm, positive to the left going
-- from its first node to its second. Zero is straight.
ALTER TABLE topology.edge ADD COLUMN IF NOT EXISTS curve_mm DECIMAL(18,3) NOT NULL DEFAULT 0;

-- Synthetic equipment behind the sample ports, so the map can say what a port belongs to.
-- Names are placeholders, not any real site's.
INSERT INTO equipment.unit(id,site_id,revision_id,code,name,kind,enabled) VALUES
 (9001,1,1,'STK-01','스토커 1','STOCKER',TRUE),
 (9002,1,1,'EQP-01','공정설비 1','PROCESS',TRUE),
 (9003,1,1,'EQP-02','공정설비 2','PROCESS',TRUE),
 (9004,1,1,'BUF-01','버퍼 1','BUFFER',TRUE),
 (9011,1,1,'STK-11','자재 창고','STOCKER',TRUE),
 (9012,1,1,'EQP-11','조립 설비','PROCESS',TRUE),
 (9013,1,1,'BUF-11','출하 버퍼','BUFFER',TRUE)
ON CONFLICT DO NOTHING;
UPDATE topology.port SET equipment_unit_id=CASE code
 WHEN 'OHT-A' THEN 9001 WHEN 'OHT-D' THEN 9001 WHEN 'OHT-B' THEN 9002 WHEN 'OHT-C' THEN 9003 WHEN 'OHT-E' THEN 9004
 WHEN 'AGV-A' THEN 9011 WHEN 'AGV-B' THEN 9012 WHEN 'AGV-C' THEN 9013 END
 WHERE site_id=1 AND revision_id=1 AND equipment_unit_id IS NULL
   AND code IN ('OHT-A','OHT-B','OHT-C','OHT-D','OHT-E','AGV-A','AGV-B','AGV-C');
