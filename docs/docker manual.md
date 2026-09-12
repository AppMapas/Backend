# 🐳 

### Paso 1: Primer levantamiento
```bash
cd Backend
cp .env.example .env
```

### Paso 2: Primer Arranque 

Esto levantara tanto frontend y backend la primera vez

```bash
# Desde la carpeta Backend/:
docker compose --profile all up -d --build
```

---

## 4. Modos de Ejecución

### Opción A: Levantar Todo el Stack Completo
Ejecutar tanto backend y frontend, hay que meterse en backend carpeta

```bash
cd Backend
docker compose --profile all up -d
```

### Opción B: Levantar Solo Backend y Base de Datos
```bash
cd Backend
docker compose up -d
```

### Opción C: Levantar Solo Frontend (Vue)
```bash
cd Frontend
docker compose up -d --build
```

### Opción D: Levantar únicamente la Base de Datos
```bash
cd Backend
docker compose up -d db
```
---

## 5. Puertos y Accesos

| Servicio | Contenedor | Puerto Host | URL                                            | Credenciales por Defecto |
| :--- | :--- | :--- | :--- | :--- |
| **Frontend (Vue + Vite)** | `legal_frontend` | `5173` | [http://localhost:5173](http://localhost:5173) | N/A |
| **Backend (Spring Boot)** | `legal_backend` | `8080` | [http://localhost:8080](http://localhost:8080) | N/A |
| **PostgreSQL** | `legal_db` | `5433` | `localhost:5433` | User: `admin`  Pass: `administrador` |

---

## 6. Comandos para debug

- **Ver logs en tiempo real de todos los servicios:**
  
  ```bash
  docker compose logs -f
  ```
  
- **Ver logs solo del backend:**
  ```bash
  docker compose logs -f backend
  ```
  
- **Ver logs solo de la base de datos:**
  ```bash
  docker compose logs -f db
  ```
  
- **Ver logs solo del frontend:**
  ```bash
  docker compose logs -f frontend
  ```
