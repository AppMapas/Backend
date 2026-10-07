# ==============================================================
# STAGE 1 — Descarga de dependencias (caché de Maven)
# ==============================================================
FROM maven:3.9-eclipse-temurin-17-alpine AS base
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

# ==============================================================
# STAGE 2 — Desarrollo con hot-reload
# Las migraciones Flyway se ejecutan automáticamente al arrancar
# Spring Boot desde el classpath (src/main/resources/db/migration)
# ==============================================================
FROM base AS dev
COPY src ./src
EXPOSE 8080
CMD ["mvn", "spring-boot:run"]

# ==============================================================
# STAGE 3 — Compilación del JAR de producción
# Se ejecutan los tests antes de generar la imagen; Flyway lleva
# las migraciones empaquetadas dentro del JAR.
# ==============================================================
FROM base AS builder
COPY src ./src
RUN mvn -B clean verify

# ==============================================================
# STAGE 4 — Imagen de producción (JRE mínimo)
# El JAR incluye las migraciones Flyway en:
#   BOOT-INF/classes/db/migration/
# Spring Boot las ejecuta automáticamente al iniciar la app,
# ANTES de que Hibernate valide el esquema (ddl-auto=validate).
# No se necesita ningún script SQL externo ni entrypoint extra.
# ==============================================================
FROM eclipse-temurin:17-jre-alpine AS prod
WORKDIR /app

# Usuario sin privilegios para mayor seguridad
RUN addgroup -S spring && adduser -S spring -G spring \
    && mkdir -p /app/data/documents && chown -R spring:spring /app/data
USER spring:spring

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Variables de entorno requeridas en tiempo de ejecución
# (inyectadas por docker-compose o por el orquestador)
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-jar", "app.jar"]
