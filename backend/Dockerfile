# Build stage: Maven with a JDK. Dependencies are a separate layer, so they are only fetched again when pom.xml changes.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/backend-*.jar app.jar

# Runtime stage: JRE only, unprivileged user.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
EXPOSE 8080
# MANAGEMENT_PORT is the actuator port (8081 in production, where it is not published).
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -q -O /dev/null "http://localhost:${MANAGEMENT_PORT:-${SERVER_PORT:-8080}}/actuator/health" || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
