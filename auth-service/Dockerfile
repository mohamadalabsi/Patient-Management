
FROM eclipse-temurin:21-jre-alpine AS runner
ADD target/auth-service-0.0.1-SNAPSHOT.jar auth-service-0.0.1-SNAPSHOT.jar

ENTRYPOINT ["java", "-jar","/auth-service-0.0.1-SNAPSHOT.jar"]