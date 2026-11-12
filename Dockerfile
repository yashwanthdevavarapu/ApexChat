# syntax=docker/dockerfile:1.7

# ---- Build -----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/apexchat.jar extract --layers --launcher --destination /extracted

# ---- Runtime ---------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
LABEL org.opencontainers.image.title="ApexChat" \
      org.opencontainers.image.description="Real-time chat over WebSocket + STOMP with Spring Boot" \
      org.opencontainers.image.licenses="MIT"

RUN addgroup -S apex && adduser -S apex -G apex
WORKDIR /app

# Least- to most-frequently changing layers, so code changes only rebuild the last one
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER apex
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness >/dev/null || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
