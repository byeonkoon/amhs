-- Equipment the controller serves, kept apart from the resources it commands. A stocker is not a
-- vehicle and not a node: it has racks and cells, and the controller meets it at a port.
CREATE SCHEMA equipment;

CREATE TABLE equipment.unit(
 id BIGINT NOT NULL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 revision_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(128) NOT NULL DEFAULT '',
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('STOCKER','BUFFER','PROCESS')),
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_equipment_code UNIQUE(site_id,revision_id,code));

-- A rack is a grid. Its size is configuration; the cells it implies are generated from it so the
-- two cannot disagree.
CREATE TABLE equipment.rack(
 id BIGINT NOT NULL PRIMARY KEY,
 unit_id BIGINT NOT NULL REFERENCES equipment.unit(id),
 code VARCHAR(64) NOT NULL,
 levels INT NOT NULL CHECK(levels BETWEEN 1 AND 60),
 columns INT NOT NULL CHECK(columns BETWEEN 1 AND 200),
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_rack_code UNIQUE(unit_id,code));

-- usable is configuration: somebody decided this cell is not to be filled. It is deliberately not
-- occupancy, not a reservation and not where a carrier is; those are different facts with
-- different lifetimes, and merging them is how a blocked cell becomes an occupied one by accident.
CREATE TABLE equipment.slot(
 id BIGSERIAL PRIMARY KEY,
 rack_id BIGINT NOT NULL REFERENCES equipment.rack(id),
 level INT NOT NULL,
 col INT NOT NULL,
 code VARCHAR(64) NOT NULL,
 usable BOOLEAN NOT NULL DEFAULT TRUE,
 unusable_reason VARCHAR(128),
 CONSTRAINT uq_slot_cell UNIQUE(rack_id,level,col));
CREATE INDEX ix_slot_rack ON equipment.slot(rack_id,level,col);

-- The port is where the controller hands over to the equipment, so the link lives on the port.
ALTER TABLE topology.port ADD COLUMN IF NOT EXISTS equipment_unit_id BIGINT REFERENCES equipment.unit(id);
