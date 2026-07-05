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
RUN apk add --no-cache github-cli \
 && addgroup -S gc && adduser -S gc -G gc
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER gc
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
