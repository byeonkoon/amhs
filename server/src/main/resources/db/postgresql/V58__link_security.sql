-- Who is at the other end of the link, and whether anybody can listen in.
--
-- A dial-out link trusted whatever answered on that address, and a dial-in link trusted whatever
-- arrived and said a vehicle's name. The name is not a secret: it is in the map file, in the
-- screens, and in every frame on the wire. So anything that could reach the port could drive the
-- fleet, and anything on the path could read and change what went past.
--
-- These two columns are the whole of the configuration. The key material itself is NOT here: a
-- private key in a database row is a private key in every backup, every replica and every dump.
-- It lives in files the operator puts on the server, named by `amhs.device.tls.*`, and the
-- procedure is in docs/link-security.md.
ALTER TABLE integration.connection_profile
 -- Whether the link is TLS. There is no fallback: a link that asks for TLS and cannot have it does
 -- not open. Quietly carrying on in plaintext is the one behaviour that would make this pointless.
 ADD COLUMN IF NOT EXISTS tls BOOLEAN NOT NULL DEFAULT FALSE,
 -- The name the far side's certificate must carry. Empty means the address we dialled, which is
 -- what a browser checks. It is here because a plant is dialled by address as often as by name,
 -- and a certificate issued to a name cannot be checked against an address without saying so.
 --
 -- For a dial-in profile it is the name the gateway's own certificate must carry, and it is the
 -- whole of "who arrived": the vehicle it then claims is what it may speak for, which is a
 -- separate question with a separate answer (the communication rows).
 ADD COLUMN IF NOT EXISTS peer_name VARCHAR(255);

-- A name to check with nothing to check it on is a setting that does nothing, which is worse than
-- one that is missing: it reads as protection.
ALTER TABLE integration.connection_profile DROP CONSTRAINT IF EXISTS ck_peer_name_needs_tls;
ALTER TABLE integration.connection_profile ADD CONSTRAINT ck_peer_name_needs_tls
 CHECK(peer_name IS NULL OR tls);
