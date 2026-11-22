FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
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
RUN java -Djarmode=tools -jar /workspace/target/auth-service-0.0.1-SNAPSHOT.jar extract --layers \
    --destination /workspace/extracted --application-filename app.jar

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
# PHASE 2 (old): COPY --from=build --chown=spring:spring /workspace/target/auth-service-0.0.1-SNAPSHOT.jar app.jar
# PHASE 2 (step 2.3): one COPY per layer, from least to most frequently changing,
# so Docker can reuse the earlier layers when only our code changed.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring

EXPOSE 4005

ENTRYPOINT ["java", "-jar", "app.jar"]
