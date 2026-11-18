# syntax=docker/dockerfile:1

# ---- build -----------------------------------------------------------------
# Pinned JDK 21 + Maven. Tests are skipped: the integration suite needs Docker
# (Testcontainers) and is run by CI, not inside the image build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependencies first: this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -q package -DskipTests

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# Non-root: the server only needs to read mounted roots and write its audit file.
RUN useradd --system --create-home --uid 10001 app

WORKDIR /app
COPY --from=build /src/target/mcp-dev-tooling-0.1.0.jar /app/server.jar
# Audit + rolling server log live here. Pre-create with the right owner so a
# named volume mounted over it inherits app:app permissions.
RUN mkdir -p /home/app/.mcp-dev-tooling/logs \
    && chown -R app:app /app /home/app/.mcp-dev-tooling

USER app
ENV HOME=/home/app

# stdio by default; the optional HTTP profile listens on 8080 (see README).
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
