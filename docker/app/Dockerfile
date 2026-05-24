# ── Build stage ─────────────────────────────────────────────
FROM eclipse-temurin:26-jdk-alpine AS builder
WORKDIR /build

# 의존성 캐싱 — gradle wrapper와 빌드 스크립트만 먼저 복사
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 jar 빌드
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ── Runtime stage ───────────────────────────────────────────
FROM eclipse-temurin:26-jre-alpine
WORKDIR /app

# wget for healthcheck
RUN apk add --no-cache wget

COPY --from=builder /build/build/libs/*.jar app.jar

EXPOSE 8080

# JVM 옵션 — 컨테이너 메모리 인식 + G1GC
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
