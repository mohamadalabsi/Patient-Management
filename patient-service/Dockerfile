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

# PHASE 2 (step 2.3): split the fat jar into LAYERS (Spring Boot "tools" jar mode).
# A Docker image is a stack of layers; a push/pull only transfers layers that changed.
# One 60 MB fat jar = one layer that changes on EVERY commit. Split up, the big
# rarely-changing part (libraries) gets its own layer, and a code change only
# re-uploads the small "application" layer (a few hundred KB):
#   dependencies/          -> lib/*.jar of released libraries   (changes when the pom changes)
#   spring-boot-loader/    -> Spring's launcher classes         (empty in this mode)
#   snapshot-dependencies/ -> lib/*-SNAPSHOT.jar                (none here)
#   application/           -> app.jar: our classes + resources, finds its libraries in lib/
RUN java -Djarmode=tools -jar /workspace/patient-service/target/patient-service-0.0.1-SNAPSHOT.jar extract --layers \
    --destination /workspace/extracted --application-filename app.jar

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
# PHASE 0 (old): COPY --from=build --chown=spring:spring /workspace/target/patient-service-0.0.1-SNAPSHOT.jar app.jar
# PHASE 2 (old): COPY --from=build --chown=spring:spring /workspace/patient-service/target/patient-service-0.0.1-SNAPSHOT.jar app.jar
# PHASE 2 (step 2.3): one COPY per layer, from least to most frequently changing,
# so Docker can reuse the earlier layers when only our code changed.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring

EXPOSE 4000

ENTRYPOINT ["java", "-jar", "app.jar"]
