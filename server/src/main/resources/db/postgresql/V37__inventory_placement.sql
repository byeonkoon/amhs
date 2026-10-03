-- Where a carrier is when nothing is carrying it.
--
-- The transport side already says where a carrier is while an order has it: at the source, on the
-- vehicle, at the destination, or unknown. That answer ends when the order ends, and a carrier
-- sitting in a stocker for a week is not part of any order. This is the other half.
--
-- Two things are deliberately kept apart from this, because merging them is how a blocked cell
-- becomes an occupied one by accident:
--   - equipment.slot.usable is configuration: somebody decided this cell is not to be filled. A
--     cell can be both unusable and holding something, which is a situation a person needs to see
--     rather than a contradiction to resolve by picking one.
--   - an order's carrier_location is a belief about a carrier in motion, with the order's lifetime.
--
-- A port placement means the carrier was handed over at that port and nobody has said which cell it
-- went into. The controller does not talk to the equipment's own storage system, so the cell is not
-- something it can know; recording the port and admitting the cell is unknown is the honest answer,
-- and it is different from the carrier being lost.
CREATE SCHEMA inventory;

CREATE TABLE inventory.placement(
 id BIGSERIAL PRIMARY KEY,
 site_id BIGINT NOT NULL REFERENCES platform.site(id),
 carrier_code VARCHAR(64) NOT NULL,
 slot_id BIGINT REFERENCES equipment.slot(id),
 port_id BIGINT REFERENCES topology.port(id),
 -- How it is known. DEVICE is reserved for an equipment interface reporting its own contents;
 -- nothing writes it yet, and a value nothing writes is better than a person's word recorded as a
 -- machine's.
 evidence VARCHAR(16) NOT NULL CHECK(evidence IN ('OPERATOR','DEVICE')),
 -- How the person knows. Required of a person, for the same reason the transport side requires it:
 -- "somebody said so" without saying how they know is not evidence.
 reason VARCHAR(256) NOT NULL,
 actor_code VARCHAR(64),
 placed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 version BIGINT NOT NULL DEFAULT 0,
 -- A carrier is in one place. Two rows saying otherwise is not two carriers.
 CONSTRAINT uq_placement_carrier UNIQUE(site_id,carrier_code),
 CONSTRAINT ck_placement_one_place CHECK(
  (slot_id IS NOT NULL)::int+(port_id IS NOT NULL)::int=1));

-- A cell holds at most one carrier, and the database is what decides that. Two people recording
-- different carriers into the same cell at the same moment both read it as empty; only this stops
-- the second one.
CREATE UNIQUE INDEX uq_placement_slot ON inventory.placement(slot_id) WHERE slot_id IS NOT NULL;
CREATE INDEX ix_placement_site ON inventory.placement(site_id,carrier_code);
