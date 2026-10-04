# syntax=docker/dockerfile:1.7
# Shared template for all 8 services: only MODULE and PORT differ. Build from the repository root:
#   docker build -f services/order-service/Dockerfile .
ARG MODULE=services/order-service
ARG PORT=8082

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace
COPY pom.xml ./
COPY ${MODULE}/pom.xml ${MODULE}/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f ${MODULE}/pom.xml dependency:go-offline
COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f ${MODULE}/pom.xml -DskipTests package \
    && java -Djarmode=tools -jar ${MODULE}/target/*.jar extract --layers --launcher --destination /workspace/layers

FROM eclipse-temurin:21-jre-alpine
ARG PORT
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/layers/application/ ./
USER 10001
ENV PORT=${PORT} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE ${PORT}
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health" > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
