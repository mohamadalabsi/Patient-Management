
FROM eclipse-temurin:21-jre-alpine AS runner
ADD target/patient-service-0.0.1-SNAPSHOT.jar patient-service-0.0.1-SNAPSHOT.jar

ENTRYPOINT ["java", "-jar","/patient-service-0.0.1-SNAPSHOT.jar"]