# -----------------------------------------------------------------------------
# PHASE 0 (step 0.3): this image is now built with the REPO ROOT as build context
# (docker-compose.yml: context: . , dockerfile: patient-service/Dockerfile).
#
# Why: the .proto contracts moved to the shared root folder proto/. Docker can
# only COPY files that are inside the build context, and the old context
# (./patient-service) could not see ../proto. So all COPY paths now start with "patient-service/".
# The folder layout inside the image mirrors the repo
#   /workspace/patient-service   and   /workspace/proto
# so that pom.xml's  ${project.basedir}/../proto  resolves the same way as on
# your machine. The root .dockerignore keeps the context small.
# -----------------------------------------------------------------------------

# PHASE 0 (old):
#FROM maven:3.9.9-eclipse-temurin-21 AS build
#
#WORKDIR /workspace
#
#COPY pom.xml .
#RUN mvn -B dependency:go-offline
#
#COPY src ./src
#RUN mvn -B clean package -DskipTests

FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace/patient-service

# 1) Only the pom first: Docker caches this layer, so dependencies are downloaded
#    again only when pom.xml changes - not on every source change.
COPY patient-service/pom.xml .
RUN mvn -B dependency:go-offline

# 2) Shared contracts + this service's sources, then build the jar.
COPY proto /workspace/proto
COPY patient-service/src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
# PHASE 0 (old): COPY --from=build --chown=spring:spring /workspace/target/patient-service-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build --chown=spring:spring /workspace/patient-service/target/patient-service-0.0.1-SNAPSHOT.jar app.jar

USER spring

EXPOSE 4000

ENTRYPOINT ["java", "-jar", "app.jar"]
