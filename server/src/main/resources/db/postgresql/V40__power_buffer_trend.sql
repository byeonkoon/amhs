-- A vehicle fed from the rail has no charging cycle, so a threshold on its state of charge answers
-- the wrong question. What matters is the direction: a buffer that only falls is a vehicle that is
-- not picking its power up. A threshold would notice far too late, and wherever the vehicle happened
-- to be -- which on a single loop is behind everything else.
--
-- Seeing a direction needs more than the latest value, and the state row holds only the latest one.
-- The highest reading since the last rise is kept instead of a history: it is the whole of what the
-- rule asks, it is one row, and a rise resets it, which is exactly the event that means the vehicle
-- is being fed again.
ALTER TABLE fleet.vehicle_state
 ADD COLUMN IF NOT EXISTS soc_peak_pct DOUBLE PRECISION,
 ADD COLUMN IF NOT EXISTS soc_peak_at TIMESTAMPTZ;

-- Starting from what is already known, so an existing vehicle is not treated as having just risen.
UPDATE fleet.vehicle_state SET soc_peak_pct=battery_pct,soc_peak_at=now()
 WHERE battery_pct IS NOT NULL AND soc_peak_pct IS NULL;

-- How long a state of charge may fall without rising before it is called a fault, and how far it
-- must have fallen over that time to count. Null in either leaves the rule off: a site that does
-- not want it says so by leaving it unset, not by setting a value that never triggers.
ALTER TABLE platform.site_operation
 ADD COLUMN IF NOT EXISTS power_drop_window_seconds INT,
 ADD COLUMN IF NOT EXISTS power_drop_min_pct INT;
ALTER TABLE platform.site_operation ADD CONSTRAINT ck_power_drop_window
 CHECK(power_drop_window_seconds IS NULL OR power_drop_window_seconds BETWEEN 30 AND 86400);
ALTER TABLE platform.site_operation ADD CONSTRAINT ck_power_drop_min
 CHECK(power_drop_min_pct IS NULL OR power_drop_min_pct BETWEEN 1 AND 100);

-- The simulated site watches for it, so the rule is exercised rather than merely available.
UPDATE platform.site_operation SET power_drop_window_seconds=180,power_drop_min_pct=5
 WHERE power_drop_window_seconds IS NULL AND power_drop_min_pct IS NULL;
