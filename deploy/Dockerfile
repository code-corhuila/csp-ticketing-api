FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -DskipTests -pl app -am package

FROM eclipse-temurin:21-jre
COPY --from=build --chown=10001:10001 /src/app/target/ticketing-app.jar /app.jar
USER 10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app.jar"]
