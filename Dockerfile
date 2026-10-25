# Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Cache Gradle wrapper and dependencies separately from sources
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /workspace/build/libs/*.jar app.jar

# Server has ~1GB RAM: cap heap relative to the container memory limit.
# 40% of 640m (256MB) leaves room for metaspace (~100MB) and threads; at 60% the container hit its limit and swapped.
# The server sustains only ~1/8 OCPU, so C1 only: C2 compiles took CPU from requests for minutes after each deploy.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
