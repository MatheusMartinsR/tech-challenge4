FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY functions/ingestion/pom.xml ./pom.xml
COPY functions/ingestion/src ./src
RUN mvn -B -q package -Plocal -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/ingestion-1.0.0.jar /app/ingestion.jar
EXPOSE 3000
ENTRYPOINT ["java", "-cp", "/app/ingestion.jar", "br.com.fiap.tc4.ingestion.LocalApiServer"]
