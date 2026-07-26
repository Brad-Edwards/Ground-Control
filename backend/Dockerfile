FROM node:22-alpine AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
COPY contracts/gen/typescript/ /contracts/gen/typescript/
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY backend/gradle gradle
COPY backend/gradlew backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties backend/gradle.lockfile ./
RUN ./gradlew dependencies --no-daemon
COPY backend/src src
COPY --from=frontend /app/dist src/main/resources/static/
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
# `apk upgrade` before installing: the temurin base tag lags Alpine's security
# updates, so it currently ships fixable HIGH CVEs (libexpat CVE-2026-56131 /
# -56407 / -56408, p11-kit CVE-2026-2100). Patching here is what lets the CI
# trivy gate block on fixable CRITICAL/HIGH instead of being report-only, and it
# means the image we actually ship carries the fixes rather than waiting on an
# upstream tag refresh.
RUN apk --no-cache upgrade \
 && apk add --no-cache github-cli \
 && addgroup -S gc && adduser -S gc -G gc
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER gc
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
