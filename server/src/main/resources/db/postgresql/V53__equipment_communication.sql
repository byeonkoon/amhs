-- How a piece of equipment is reached, and what it last said.
--
-- Until now equipment had a state only in simulation: `equipment.simulation_state` was the one
-- place a stocker could be busy or refusing calls, and a site in OPERATION therefore knew nothing
-- about its equipment at all. Transport asked whether a unit would take a carrier and, outside
-- simulation, got no answer -- so it assumed yes.
--
-- These are the two halves of fixing that. One says where the machine is; the other says what it
-- told us, kept apart from both the configuration and the simulated state so that none of the
-- three can be mistaken for another.

-- Which connection and protocol profile reach this unit, and what the far side calls it. The same
-- two profiles a vehicle uses: a stocker behind a PLC is the same arrangement as an AGV behind one,
-- and having a second kind of connection profile would mean maintaining the same thing twice.
CREATE TABLE IF NOT EXISTS equipment.communication(
 id BIGINT NOT NULL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 unit_id BIGINT NOT NULL REFERENCES equipment.unit(id),
 connection_profile_id BIGINT NOT NULL REFERENCES integration.connection_profile(id),
 protocol_profile_id BIGINT NOT NULL REFERENCES integration.protocol_profile(id),
 external_unit_id VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_equipment_communication_unit UNIQUE(site_id,unit_id),
 CONSTRAINT uq_equipment_communication_external UNIQUE(site_id,connection_profile_id,external_unit_id));

-- What the device said, and when. Deliberately not merged with `simulation_state`: one is what a
-- machine reports and the other is what somebody set to try something out, and a site that cannot
-- tell them apart cannot tell a real fault from a rehearsal.
--
-- `online` is this controller's judgement rather than the device's claim: it is false when nothing
-- has been read within the time the mapping polls at, which is the only way a block of registers
-- can say its controller has gone.
CREATE TABLE IF NOT EXISTS equipment.device_state(
 unit_id BIGINT NOT NULL PRIMARY KEY REFERENCES equipment.unit(id) ON DELETE CASCADE,
 available BOOLEAN,
 call_enabled BOOLEAN,
 status VARCHAR(16) CHECK(status IS NULL OR status IN
  ('IDLE','READY','BUSY','ERROR','OFFLINE','LOADING','UNLOADING')),
 online BOOLEAN NOT NULL DEFAULT FALSE,
 last_seen_at TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp());
