-- Run once as a PostgreSQL administrator in psql, connected to postgres.
-- Existing roles/databases are never dropped or overwritten. Stop on any error.
\set ON_ERROR_STOP on
CREATE ROLE amhs LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password amhs
CREATE DATABASE amhs_dev OWNER amhs ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE amhs_test OWNER amhs ENCODING 'UTF8' TEMPLATE template0;
