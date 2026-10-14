-- Equipment belongs to the site, not to a map revision.
--
-- It was tied to one, and the tie never held: nothing copied the units when a site moved to a new
-- map, so every unit stayed behind on the revision it was made under. A site that imported a map
-- ended up with its stocker listed in one panel and absent from the next, and transport asking
-- "will this unit take a carrier" found no unit to ask about.
--
-- Of the two ways to fix that -- carry the units forward with each revision, or stop tying them --
-- the second is the one the rest of the schema already assumes:
--
--   * A unit refers to no node. The reference runs the other way: topology.port points at a unit.
--     Nothing about a unit changes when the track around it is redrawn.
--   * inventory placements point at equipment.slot. That a carrier sits in a cell stays true when
--     somebody redraws the map, and a unit that vanished with its revision would leave those rows
--     pointing at nothing.
--   * Racks and cells describe the physical shelf. They are not map data in any sense.
--
-- A vehicle is tied to a revision for the opposite reason: it names its initial, home and charge
-- nodes, so it cannot outlive the track those names belong to.

-- A code that was used on two revisions would collide under the new rule. It cannot be guessed
-- which one was meant, so this stops with the codes named rather than failing on a constraint.
DO $$
DECLARE clashing TEXT;
BEGIN
 SELECT string_agg(DISTINCT code,', ') INTO clashing
 FROM equipment.unit GROUP BY site_id,code HAVING count(*)>1;
 IF clashing IS NOT NULL THEN
  RAISE EXCEPTION 'equipment codes used on more than one map revision: %. '
   'Equipment is becoming site-wide, so each code may exist once per site. '
   'Remove or rename the duplicates, then migrate again.',clashing;
 END IF;
END $$;

ALTER TABLE equipment.unit DROP CONSTRAINT IF EXISTS uq_equipment_code;
ALTER TABLE equipment.unit ADD CONSTRAINT uq_equipment_code UNIQUE(site_id,code);
ALTER TABLE equipment.unit DROP COLUMN IF EXISTS revision_id;
