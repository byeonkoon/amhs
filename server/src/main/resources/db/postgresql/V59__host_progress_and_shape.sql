-- Two things a system upstairs asks for once it has been told how work ends.
--
-- The first is what is happening to it: a vehicle was given to the order, the carrier is on that
-- vehicle. Until now only the ends were said -- it finished, it was cancelled, it needs a person --
-- so an order that takes twenty minutes looked identical to one that had not been started.
--
-- The second is to be told it in its own words. The body this controller writes is the body this
-- controller thinks in, and a host that calls a transfer job a MOVE_REQ with a FROM_LOC is not
-- going to be rewritten for us. That translation is a mapping document, the way a vehicle's
-- dialect is: the decision of what to say stays here, and how it is spelled lives at the site.
ALTER TABLE integration.host_endpoint
 -- Off unless somebody asks for it. A host that only wants the ends gets the ends, and a progress
 -- notice nobody wants is a retry schedule nobody wants either.
 ADD COLUMN IF NOT EXISTS progress_notices BOOLEAN NOT NULL DEFAULT FALSE,
 -- The document that puts a notice into the host's own words, by its name. Null means this
 -- controller's own shape, which is what every site has had until now.
 ADD COLUMN IF NOT EXISTS mapping_code VARCHAR(64);
