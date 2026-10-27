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
# 감사 로그 파일 위치(src/main/resources/logback-spring.xml). deploy.yml이 named volume을 여기에 붙인다.
# 빈 volume은 처음 붙일 때 이 디렉터리의 소유자(spring)를 그대로 받으므로 앱이 쓸 수 있다.
RUN mkdir /app/logs && chown spring /app/logs
ENV AUDIT_LOG_DIR=/app/logs
USER spring

COPY --from=build /workspace/build/libs/*.jar app.jar

# Server has ~1GB RAM: cap heap relative to the container memory limit.
# 40% of 640m (256MB) leaves room for metaspace (~100MB) and threads; at 60% the container hit its limit and swapped.
# The server sustains only ~1/8 OCPU, so C1 only: C2 compiles took CPU from requests for minutes after each deploy.
# ExitOnOutOfMemoryError: 힙이 바닥나면 JVM을 바로 종료한다. 그래야 `--restart`가 컨테이너를 다시 띄운다.
# 없으면 OOM 뒤 Full GC만 반복하며 멈춘 상태로 남을 수 있다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
