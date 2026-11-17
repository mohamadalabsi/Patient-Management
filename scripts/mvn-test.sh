#!/usr/bin/env sh
# -----------------------------------------------------------------------------
# PHASE 1 (step 1.1): run the Maven tests of ONE service without a local JDK.
#
# Usage (from the repo root):
#   scripts/mvn-test.sh patient-service                        # all tests
#   scripts/mvn-test.sh patient-service -Dtest=PatientMapperTest
#   scripts/mvn-test.sh patient-service -Dtest='PatientServiceTest#create*'
#
# Only Docker is needed - no compose stack, no .env.
#
# How it works:
# - The tests run inside the official maven image (JDK 21 + Maven).
# - The service folder AND the shared proto/ folder are mounted with the same
#   layout as the repo, so pom.xml's ${project.basedir}/../proto still resolves.
# - pm-m2-cache is a named volume = Maven's ~/.m2, so dependencies are downloaded once.
# - Testcontainers needs to start "sibling" containers (Postgres, Kafka). It talks to
#   the Docker daemon through the mounted socket /var/run/docker.sock.
# - The started containers publish their ports on the HOST, not inside this maven
#   container. TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal tells Testcontainers to
#   connect via the host (Docker Desktop provides that DNS name; --add-host makes it
#   work on plain Linux Docker too).
# With a local JDK you can instead run "./mvnw test" inside the service folder.
# -----------------------------------------------------------------------------
set -eu

SERVICE="${1:?usage: scripts/mvn-test.sh <service-folder> [extra maven args]}"
shift
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$ROOT/$SERVICE/pom.xml" ]; then
  echo "No pom.xml in $ROOT/$SERVICE" >&2
  exit 1
fi

exec docker run --rm \
  -v "$ROOT/$SERVICE":/workspace/"$SERVICE" \
  -v "$ROOT/proto":/workspace/proto:ro \
  -v pm-m2-cache:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace/"$SERVICE" \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B test "$@"
