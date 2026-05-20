# syntax=docker/dockerfile:1.6

########################
# Stage 1 - Build
########################
FROM maven:3.9.6-eclipse-temurin-21 AS build

ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1"

WORKDIR /app

COPY .mvn/ .mvn
COPY mvnw pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests

########################
# Stage 2 - Runtime
########################
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/worker-0.0.1-SNAPSHOT.jar app.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

CMD ["java", "--enable-preview", "-jar", "app.jar"]