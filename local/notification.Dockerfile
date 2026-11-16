FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY functions/notification/pom.xml ./pom.xml
COPY functions/notification/src ./src
COPY template.yaml /template.yaml
RUN mvn -B -q package -Plocal -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/notification-1.0.0.jar /app/notification.jar
COPY template.yaml /app/template.yaml
ENTRYPOINT ["java", "-cp", "/app/notification.jar", "br.com.fiap.tc4.notification.LocalNotificationWorker"]
