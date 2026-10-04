-- A vehicle code is unique within one map revision, not across the whole site.
--
-- The site's maps are versions of the same place, so two of them describe the same fleet: one says
-- OHT-01 starts at this node, the next says it starts at that one. The old rule -- one code per
-- site -- made that impossible, so a second revision had to invent names for vehicles that already
-- existed, and a map written elsewhere and brought in could not name the vehicles it was about.
--
-- Within a revision the code still has to be unique: it is what a person types and what every
-- screen shows, and two of them in one map is two vehicles nobody can tell apart.
ALTER TABLE fleet.vehicle_profile DROP CONSTRAINT IF EXISTS uq_vehicle_code;
ALTER TABLE fleet.vehicle_profile ADD CONSTRAINT uq_vehicle_code UNIQUE(site_id,revision_id,code);
