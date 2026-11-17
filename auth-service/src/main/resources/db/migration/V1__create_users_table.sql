-- -----------------------------------------------------------------------------
-- PHASE 0 (step 0.5): first Flyway migration of AuthDB.
-- (How Flyway migrations work is explained in
--  patient-service/src/main/resources/db/migration/V1__create_patient_table.sql)
--
-- Replaces the "CREATE TABLE IF NOT EXISTS users" that used to live in data.sql.
-- Matches the table that exists today (checked with pg_dump).
--
-- This file contains SCHEMA only - no users. Seed data for local development is
-- in db/dev-data/, which is only enabled in docker-compose.yml.
-- auth-service will be reworked when Auth0 arrives (Phase 3); until then it
-- must keep working, so it gets proper migrations like every other service.
-- -----------------------------------------------------------------------------

CREATE TABLE users (
    id       UUID         NOT NULL,
    email    VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,   -- BCrypt hash, never the plain password
    role     VARCHAR(255) NOT NULL,

    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);
