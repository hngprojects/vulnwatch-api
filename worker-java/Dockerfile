# syntax=docker/dockerfile:1.6

########################
# Stage 1 - Build
########################
FROM maven:3.9.6-eclipse-temurin-21 AS build

ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1"

WORKDIR /app

# Copy only what is needed for dependency resolution
COPY .mvn/ .mvn
COPY mvnw pom.xml ./

# Cache Maven dependencies (THIS is the big win)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

# Now copy source
COPY src ./src

# Build with cache again
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests

########################
# Stage 2 - Runtime
########################
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]