########################
# Stage 1 - Build
########################
FROM maven:3.9.6-eclipse-temurin-21 AS build

# ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1"

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q          # cache deps layer
COPY src ./src
RUN mvn package -q -DskipTests

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