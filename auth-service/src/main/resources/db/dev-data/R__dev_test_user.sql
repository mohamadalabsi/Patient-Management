-- -----------------------------------------------------------------------------
-- PHASE 0 (step 0.5): DEVELOPMENT-ONLY seed data (fixes audit finding S5).
--
-- Before: data.sql inserted this ADMIN user in EVERY environment, because
-- spring.sql.init.mode was "always". Its password ("password123") is visible in
-- the repo, so in production it would have been a backdoor.
--
-- Now: this folder (db/dev-data) is NOT in Flyway's default locations. It is
-- only scanned when SPRING_FLYWAY_LOCATIONS includes classpath:db/dev-data,
-- which docker-compose.yml sets for local development. A production deployment
-- simply does not set it -> no test user.
--
-- "R__" = REPEATABLE migration: it has no version number. Flyway runs it after
-- all versioned migrations, and runs it again whenever the file content changes.
-- ON CONFLICT DO NOTHING makes it safe to run many times (idempotent).
--
-- The Integration-tests log in with testuser@test.com / password123.
-- -----------------------------------------------------------------------------

INSERT INTO users (id, email, password, role)
VALUES ('223e4567-e89b-12d3-a456-426614174006',
        'testuser@test.com',
        '$2b$12$7hoRZfJrRKD2nIm2vHLs7OBETy.LWenXXMLKf99W8M4PUwO6KB7fu',  -- BCrypt of "password123"
        'ADMIN')
ON CONFLICT DO NOTHING;
