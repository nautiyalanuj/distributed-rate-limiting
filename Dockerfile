FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY rate-limit-core ./rate-limit-core
COPY rate-limit-client ./rate-limit-client
COPY quota-service ./quota-service
COPY demo-service ./demo-service
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS quota-service
WORKDIR /app
COPY --from=build /workspace/quota-service/target/quota-service-1.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre AS demo-service
WORKDIR /app
COPY --from=build /workspace/demo-service/target/demo-service-1.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
