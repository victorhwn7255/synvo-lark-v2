FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY config config
COPY src src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S synvo && adduser -S synvo -G synvo

WORKDIR /app
COPY --from=build /workspace/target/synvo-assistant-0.0.1-SNAPSHOT.jar app.jar

USER synvo
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
