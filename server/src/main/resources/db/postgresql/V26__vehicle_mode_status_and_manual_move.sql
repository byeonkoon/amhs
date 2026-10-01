-- What a vehicle says about itself, on the two axes it reports them: who is driving it (mode) and
-- what it is doing (status). Remote and idle stay as the flags the controller acts on; they follow
-- from these when a vehicle reports them.
ALTER TABLE fleet.vehicle_state
 ADD COLUMN IF NOT EXISTS mode VARCHAR(12),
 ADD COLUMN IF NOT EXISTS status VARCHAR(12);
ALTER TABLE fleet.vehicle_state ADD CONSTRAINT ck_vehicle_state_mode
 CHECK(mode IS NULL OR mode IN ('MANUAL','SEMI_AUTO','REMOTE'));
ALTER TABLE fleet.vehicle_state ADD CONSTRAINT ck_vehicle_state_status
 CHECK(status IS NULL OR status IN ('IDLE','MOVING','ERROR','PAUSED','CHARGING','LOADING','UNLOADING','RESET'));

-- A move without a transport order is no longer only a way out of a zone: an operator can send a
-- vehicle to a node. The purpose says which, and an operator's move has no zone.
ALTER TABLE transport.relocation ALTER COLUMN zone_id DROP NOT NULL;
ALTER TABLE transport.relocation ADD COLUMN IF NOT EXISTS purpose VARCHAR(24) NOT NULL DEFAULT 'ZONE_EVACUATION';
ALTER TABLE transport.relocation ADD CONSTRAINT ck_relocation_purpose CHECK(purpose IN ('ZONE_EVACUATION','MANUAL'));
