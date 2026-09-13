# ⚙️ Variables de Entorno — `.env.example`

Este archivo documenta todas las variables de entorno requeridas para ejecutar el proyecto localmente o con Docker Compose.

> [!IMPORTANT]
> Copia `.env.example` como `.env` en la raíz del repositorio y completa los valores antes de levantar los servicios.
> ```bash
> cp .env.example .env
> ```

---

## Variables disponibles

### 🗄️ Base de datos (PostgreSQL)

Usadas por el contenedor `db` en Docker Compose **y** por el backend para la conexión JDBC.

| Variable            | Valor por defecto           | Descripción                                        |
|---------------------|-----------------------------|----------------------------------------------------|
| `POSTGRES_DB`       | `db_legal_administrator`    | Nombre de la base de datos                         |
| `POSTGRES_USER`     | —                           | Usuario de PostgreSQL                              |
| `POSTGRES_PASSWORD` | —                           | Contraseña del usuario de PostgreSQL               |
| `POSTGRES_PORT`     | `5433`                      | Puerto expuesto del contenedor hacia el host       |

### 🔌 Backend (Spring Boot / JDBC)

Usadas por `application.properties` para la conexión a la base de datos.

> [!NOTE]
> Cuando se ejecuta **dentro de Docker**, el host debe ser `db` (nombre del servicio en docker-compose).  
> Para ejecución **local**, usar `localhost` y el puerto `POSTGRES_PORT`.

| Variable      | Ejemplo                                                     | Descripción                        |
|---------------|-------------------------------------------------------------|------------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5433/db_legal_administrator`   | URL JDBC completa de conexión      |
| `DB_USER`     | —                                                           | Usuario de la base de datos        |
| `DB_PASSWORD` | —                                                           | Contraseña de la base de datos     |

### 🔑 Seguridad JWT

| Variable     | Descripción                                                                 |
|--------------|-----------------------------------------------------------------------------|
| `JWT_SECRET` | Clave secreta para firmar tokens JWT. Debe ser un hex de 64 caracteres (256 bits). |

Generar con:
```bash
openssl rand -hex 32
```

### 🌐 Puertos Docker

| Variable         | Valor por defecto | Descripción                             |
|------------------|-------------------|-----------------------------------------|
| `BACKEND_PORT`   | `8080`            | Puerto del backend expuesto al host     |
| `FRONTEND_PORT`  | `5173`            | Puerto del frontend expuesto al host    |

---

## Flyway y la base de datos

> [!NOTE]
> El proyecto **no usa scripts SQL externos** para inicializar el esquema. Flyway gestiona automáticamente las migraciones al arrancar Spring Boot.

Las migraciones están en:
```
legal-administrator/src/main/resources/db/migration/
├── V1__create_schema.sql
├── V2__initial_data.sql
└── V3__alter_appointment_date_to_timestamp.sql
```

Flyway se ejecuta **antes** de que Hibernate valide el esquema (`ddl-auto=validate`), garantizando que la base de datos siempre esté al día con el código.
