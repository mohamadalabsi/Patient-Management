
FROM eclipse-temurin:21-jre-alpine AS runner
ADD target/api-gateway-0.0.1-SNAPSHOT.jar api-gateway-0.0.1-SNAPSHOT.jar

ENTRYPOINT ["java", "-jar","/api-gateway-0.0.1-SNAPSHOT.jar"]