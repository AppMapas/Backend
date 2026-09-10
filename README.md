# Backend Gestor-jurídico

# Índice

# Guía de Instalación y Uso de GitFlow

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
