-- -----------------------------------------------------------------------------
-- PHASE 0 (step 0.5): first Flyway migration of PatientDB.
--
-- What is a migration?  A versioned, append-only SQL script. Flyway runs every
-- script whose version is not yet recorded in the table "flyway_schema_history",
-- in version order, exactly once. The file name is the contract:
--     V1__create_patient_table.sql
--     ^ ^  ^-- description (free text)
--     | '-- version number
--     '-- "V" = versioned migration (runs once)
--
-- RULE: never edit a migration that already ran somewhere. Flyway stores a
-- checksum of each file and refuses to start if it changed. To change the
-- schema, add a NEW file (V2__..., V3__...).
--
-- This V1 reproduces EXACTLY the table Hibernate created with ddl-auto=update
-- (checked with pg_dump), so existing databases and new databases end up with
-- the same structure. Existing databases are "baselined" instead of running
-- this file - see spring.flyway.baseline-on-migrate in application.yml.
-- -----------------------------------------------------------------------------

CREATE TABLE patient (
    id                UUID         NOT NULL,
    name              VARCHAR(255) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    address           VARCHAR(255) NOT NULL,
    birth_date        DATE         NOT NULL,
    registration_date DATE         NOT NULL,

    CONSTRAINT patient_pkey PRIMARY KEY (id),
    -- Hibernate generated a random name (ukbawli8x...); we choose a readable one.
    -- The database enforces uniqueness even if two requests race past the
    -- "existsByEmail" check in PatientService at the same time.
    CONSTRAINT uk_patient_email UNIQUE (email)
);
