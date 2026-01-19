FROM --platform=linux/amd64 openjdk:21

EXPOSE 8080

WORKDIR /app

COPY ./target/rest-service-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]