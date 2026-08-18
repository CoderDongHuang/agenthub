FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace/java-console
COPY java-console/pom.xml ./
COPY protos /workspace/protos
COPY java-console/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode -U -Daether.connector.http.retryHandler.count=5 -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S agenthub -g 10001 && adduser -S agenthub -u 10001 -G agenthub
WORKDIR /app
COPY --from=build /workspace/java-console/target/java-console-*.jar app.jar
USER 10001:10001
EXPOSE 8080 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
