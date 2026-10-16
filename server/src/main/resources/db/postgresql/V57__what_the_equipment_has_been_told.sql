-- What each handover port's equipment has been told, and how the telling went.
--
-- Until now this controller only read equipment. That was the honest position while there was
-- nothing to say: an adapter that can write is one somebody will ask to write, long before anybody
-- has decided what. The decision exists now, and it is a narrow one.
--
-- This controller knows something the equipment does not: it sent a vehicle to that port. The
-- machine finds out when the vehicle turns up. A stocker that knew a minute earlier could hold the
-- port, finish what it was doing with it, and have the carrier ready -- which is the whole reason a
-- real interface has a reservation in it.
--
-- So what is written is a STANDING STATEMENT, not a message: this is what is true at this port now.
-- That is why there is no outbox here and no retry schedule. A statement repeated says the same
-- thing, where a movement command repeated is a second movement -- and that difference is the only
-- reason writing to equipment is allowed to be this simple.
CREATE TABLE IF NOT EXISTS equipment.port_intent(
 port_id BIGINT NOT NULL PRIMARY KEY REFERENCES topology.port(id) ON DELETE CASCADE,
 unit_id BIGINT NOT NULL REFERENCES equipment.unit(id) ON DELETE CASCADE,
 -- The statement as it was written, in this controller's own words. Kept so the next pass can tell
 -- "nothing has changed" from "it has, and the machine has not been told yet", and so an operator
 -- can see what the machine was told rather than inferring it from the work.
 statement TEXT NOT NULL,
 -- Which statement this is, echoed back by the controller to say which one it took. Small on
 -- purpose (1..250, and never 0): it has to fit a byte where a document uses one, and 0 is left
 -- meaning "nothing has been said yet" so a block of zeroed registers cannot be read as a statement.
 sequence INT NOT NULL CHECK(sequence BETWEEN 1 AND 250),
 -- TOLD is the controller having echoed this statement's own sequence back. IN_DOUBT is the write
 -- having gone out with no answer -- the values are in the registers and the machine may pick them
 -- up, so nothing here pretends otherwise. NOT_TOLD is nothing having been written at all.
 state VARCHAR(16) NOT NULL CHECK(state IN ('NOT_TOLD','TOLD','IN_DOUBT')),
 last_error VARCHAR(256),
 told_at TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp());

-- The ports of one unit, which is how the writer asks: a block speaks for a controller, and a
-- controller holds the units wired to it.
CREATE INDEX IF NOT EXISTS ix_port_intent_unit ON equipment.port_intent(unit_id);
