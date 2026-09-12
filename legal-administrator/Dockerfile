
FROM maven:3.9-eclipse-temurin-17-alpine AS base
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

FROM base AS dev
COPY src ./src
EXPOSE 8080
CMD ["mvn", "spring-boot:run"]

FROM base AS builder
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS prod
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
