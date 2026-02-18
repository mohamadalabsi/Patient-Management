
FROM eclipse-temurin:21-jre-alpine AS runner
ADD target/analytics-service-0.0.1-SNAPSHOT.jar analytics-service-0.0.1-SNAPSHOT.jar

ENTRYPOINT ["java", "-jar","/analytics-service-0.0.1-SNAPSHOT.jar"]