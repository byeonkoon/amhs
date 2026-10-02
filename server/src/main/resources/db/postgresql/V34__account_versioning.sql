-- Who may do what is changed the same way as everything else: with a version, so two
-- administrators editing the same account cannot silently overwrite one another.
ALTER TABLE platform.account ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
-- Only the hash of a token is ever stored, so this says whether one was issued, never what it is.
COMMENT ON COLUMN platform.account.token_sha256 IS 'SHA-256 of the issued token; the token itself is shown once at issue and never stored';
