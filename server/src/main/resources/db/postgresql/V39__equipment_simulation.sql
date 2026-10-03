-- Simulation state is separate from equipment configuration and real device observations.
CREATE TABLE equipment.simulation_state (
 unit_id BIGINT PRIMARY KEY REFERENCES equipment.unit(id) ON DELETE CASCADE,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 call_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 status VARCHAR(16) NOT NULL DEFAULT 'IDLE'
  CHECK(status IN ('IDLE','READY','BUSY','ERROR','OFFLINE','LOADING','UNLOADING')),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
