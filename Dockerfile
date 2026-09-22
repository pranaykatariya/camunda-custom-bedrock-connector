# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependency layer (cached until pom.xml changes)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests run in CI (./mvnw verify); skip them here to keep image builds fast and hermetic.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/org-ai-agent-connector-runtime-*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Non-root user
RUN groupadd --system --gid 1001 connectors \
 && useradd --system --uid 1001 --gid connectors --home /app --shell /usr/sbin/nologin connectors
WORKDIR /app

COPY --from=build --chown=connectors:connectors /workspace/extracted/dependencies/ ./
COPY --from=build --chown=connectors:connectors /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=connectors:connectors /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=connectors:connectors /workspace/extracted/application/ ./

USER 1001:1001

# No secrets here. Provide ORG_AI_* and CAMUNDA_CLIENT_* at runtime (Kubernetes Secret, etc.).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
