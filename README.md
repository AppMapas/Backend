# 🏛️ Gestor Jurídico — Backend

**Gestor Jurídico** es una aplicación backend desarrollada con **Spring Boot 4.1 / Java 17** que da soporte a un sistema de administración para despachos legales. Permite gestionar usuarios internos (abogados, auxiliares, administradores), autenticación segura con JWT y doble factor (2FA / TOTP), así como el cálculo y registro de áreas de terrenos con conversión de unidades de medida.

### 🧰 Stack Tecnológico

| Capa               | Tecnología                                            |
|--------------------|-------------------------------------------------------|
| Framework          | Spring Boot 4.1 (WebMVC, Security, Data JPA)          |
| Lenguaje           | Java 17                                               |
| Base de datos      | PostgreSQL                                            |
| Migraciones        | Flyway                                                |
| Autenticación      | JWT (JJWT 0.11.5) + TOTP 2FA (totp-spring-boot-starter) |
| Mapeo de entidades | Lombok, Spring Data JPA                               |
| Contenerización    | Docker / Docker Compose                               |

---

## 📑 Índice de Documentación

### Endpoints

- **Documentos de expedientes:** [carga, consulta, descarga y configuración local/Google Cloud Storage](docs/documentos-expedientes.md).

| Módulo          | Ruta base              | Descripción                                                       | Documentación                              |
|-----------------|------------------------|-------------------------------------------------------------------|--------------------------------------------|
| 🔐 Auth         | `/api/v1/auth`         | Login, tokens JWT, configuración y verificación 2FA               | [auth.md](docs/endpoints/auth.md)          |
| 👥 Usuarios     | `/api/v1/users`        | CRUD de usuarios del sistema (abogados, administradores)          | [users.md](docs/endpoints/users.md)        |
| 📐 Cálculos     | `/api/calculations`    | Conversión de unidades y cálculo/registro de áreas de terrenos    | [calculations.md](docs/endpoints/calculations.md) |

### Configuración e infraestructura

| Recurso              | Descripción                                                                 | Documentación              |
|----------------------|-----------------------------------------------------------------------------|----------------------------|
| ⚙️ Variables de entorno | Variables requeridas para el backend y Docker Compose, con guía de Flyway | [env.md](docs/env.md)      |

---

## 🔒 Seguridad

- Los endpoints de `/api/v1/auth/**` son **públicos**.
- Todo lo demás requiere un **Bearer JWT** válido en el header `Authorization`.
- El registro y eliminación de usuarios están restringidos a los roles `Administrador` y `Abogada`.

---

## 🚀 Guía de Instalación y Uso de GitFlow

* **Actualización del Repositorio:** Asegúrate de estar en tu rama de integración y sincroniza los últimos cambios antes de empezar cualquier desarrollo.
  * Cambia a la rama develop: `git checkout develop`
  * Trae los cambios remotos: `git pull origin develop`

---

## 1. Instalación de GitFlow

GitFlow requiere una extensión adicional en la terminal dependiendo de tu sistema operativo. Utiliza el comando correspondiente para instalarlo:

* **En Ubuntu / Debian / Linux:** `sudo apt-get install git-flow`
* **En macOS (con Homebrew):** `brew install git-flow-avh`
* **En Windows (Git Bash):** Instalación mediante gestores de paquetes como Chocolatey (`choco install gitflow`)

---

## 2. Inicialización en el Proyecto

Configura la estructura base ejecutando el asistente interactivo en la raíz de tu proyecto:

* **Iniciar el asistente:** `git flow init`
* **Configuración de ramas:** Acepta los valores predeterminados presionando **Enter** en cada una de las opciones del asistente para establecer `main`, `develop` y los prefijos estándar (`feature/`, `release/`, `hotfix/`).

---

## 3. Flujo Diario de Trabajo (Features)

Para desarrollar cada nueva Historia de Usuario (por ejemplo, `HU-01`), sigue los comandos de control de versiones:

* **Crear e iniciar una funcionalidad:** `git flow feature start HU-01-conversion-varas` (crea y cambia automáticamente a una rama limpia derivada de `develop`).
* **Guardar avances locales:** Registra tus cambios frecuentemente con commits descriptivos:
  * `git add .`
  * `git commit -m "feat(backend): descripcion de los cambios realizados"`
* **Finalizar la funcionalidad:** `git flow feature finish HU-01-conversion-varas` (cierra la rama temporal y fusiona los cambios de forma automática en `develop`).
