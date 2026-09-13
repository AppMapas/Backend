# 👥 Usuarios del Sistema — `/api/v1/users`

Módulo para gestionar los usuarios internos del sistema (abogados, administradores). Permite crear, consultar, actualizar y eliminar usuarios, así como controlar el estado del 2FA por usuario.

> [!IMPORTANT]
> La mayoría de endpoints requieren autenticación con **Bearer JWT**. El registro y eliminación de usuarios está restringido a los roles `Administrador` y `Abogada`.

---

## GET `/api/v1/users`

Obtiene la lista de todos los usuarios registrados en el sistema.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Response `200 OK`

```json
[
  {
    "dpi": "1234567890123",
    "firstName": "María",
    "lastName": "López",
    "age": 35,
    "email": "abogada@despacho.com",
    "maritalStatusName": "Soltera",
    "nationalityName": "Guatemala",
    "roleName": "Abogada",
    "createdAt": "2025-01-15",
    "twoFactorEnabled": true
  }
]
```

| Campo               | Tipo    | Descripción                                    |
|---------------------|---------|------------------------------------------------|
| `dpi`               | String  | DPI del usuario (clave primaria, máx 15 chars) |
| `firstName`         | String  | Nombre del usuario                             |
| `lastName`          | String  | Apellido del usuario                           |
| `age`               | Integer | Edad del usuario                               |
| `email`             | String  | Correo electrónico                             |
| `maritalStatusName` | String  | Nombre del estado civil                        |
| `nationalityName`   | String  | Nombre de la nacionalidad                      |
| `roleName`          | String  | Nombre del rol asignado                        |
| `createdAt`         | Date    | Fecha de creación (`YYYY-MM-DD`)               |
| `twoFactorEnabled`  | Boolean | Estado del doble factor de autenticación       |

---

## GET `/api/v1/users/{dpi}`

Obtiene la información de un usuario específico por su DPI.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Path Parameters

| Parámetro | Tipo   | Descripción    |
|-----------|--------|----------------|
| `dpi`     | String | DPI del usuario |

### Response `200 OK`

```json
{
  "dpi": "1234567890123",
  "firstName": "María",
  "lastName": "López",
  "age": 35,
  "email": "abogada@despacho.com",
  "maritalStatusName": "Soltera",
  "nationalityName": "Guatemala",
  "roleName": "Abogada",
  "createdAt": "2025-01-15",
  "twoFactorEnabled": true
}
```

### Response `404 Not Found`

> No retorna cuerpo. El usuario con el DPI proporcionado no fue encontrado.

---

## POST `/api/v1/users/register`

Registra un nuevo usuario en el sistema.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** `Administrador`, `Abogada`

### Request Body

```json
{
  "dpi": "1234567890123",
  "firstName": "Carlos",
  "lastName": "Pérez",
  "age": 28,
  "email": "carlos.perez@despacho.com",
  "password": "contraseñaSegura123",
  "idMaritalStatus": 1,
  "idNationality": 1,
  "idRole": 2
}
```

| Campo            | Tipo    | Obligatorio | Validaciones                                         |
|------------------|---------|-------------|------------------------------------------------------|
| `dpi`            | String  | ✅ Sí        | Máximo 15 caracteres                                 |
| `firstName`      | String  | ✅ Sí        | Máximo 100 caracteres                                |
| `lastName`       | String  | ✅ Sí        | Máximo 100 caracteres                                |
| `age`            | Integer | ✅ Sí        | No puede ser nulo                                    |
| `email`          | String  | ✅ Sí        | Formato de correo válido                             |
| `password`       | String  | ✅ Sí        | Mínimo 6 caracteres                                  |
| `idMaritalStatus`| Long    | ✅ Sí        | ID del estado civil (tabla `marital_status`)         |
| `idNationality`  | Long    | ✅ Sí        | ID de la nacionalidad (tabla `country`)              |
| `idRole`         | Long    | ✅ Sí        | ID del rol (tabla `role`)                            |

### Response `200 OK`

```json
{
  "dpi": "1234567890123",
  "firstName": "Carlos",
  "lastName": "Pérez",
  "age": 28,
  "email": "carlos.perez@despacho.com",
  "maritalStatusName": "Soltero",
  "nationalityName": "Guatemala",
  "roleName": "Auxiliar",
  "createdAt": "2026-09-12",
  "twoFactorEnabled": false
}
```

---

## PUT `/api/v1/users/profile/{dpi}`

Actualiza el perfil completo de un usuario existente.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Path Parameters

| Parámetro | Tipo   | Descripción    |
|-----------|--------|----------------|
| `dpi`     | String | DPI del usuario |

### Request Body

```json
{
  "dpi": "1234567890123",
  "firstName": "Carlos",
  "lastName": "Pérez Martínez",
  "age": 29,
  "email": "carlos.nuevo@despacho.com",
  "password": "nuevaContraseña456",
  "idMaritalStatus": 2,
  "idNationality": 1,
  "idRole": 2
}
```

> Mismos campos y validaciones que el registro.

### Response `200 OK`

> Retorna el objeto `UserSystemEntity` con todos los datos del usuario actualizado.

---

## PATCH `/api/v1/users/password/{dpi}`

Actualiza únicamente la contraseña de un usuario.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Path Parameters

| Parámetro | Tipo   | Descripción    |
|-----------|--------|----------------|
| `dpi`     | String | DPI del usuario |

### Request Body

```json
{
  "password": "nuevaContraseñaSegura789"
}
```

| Campo      | Tipo   | Obligatorio | Descripción       |
|------------|--------|-------------|-------------------|
| `password` | String | ✅ Sí        | Nueva contraseña  |

### Response `204 No Content`

> No retorna cuerpo. La contraseña fue actualizada correctamente.

---

## DELETE `/api/v1/users/{dpi}`

Elimina un usuario del sistema por su DPI.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** `Administrador`, `Abogada`

### Path Parameters

| Parámetro | Tipo   | Descripción    |
|-----------|--------|----------------|
| `dpi`     | String | DPI del usuario |

### Response `204 No Content`

> No retorna cuerpo. El usuario fue eliminado correctamente.

---

## PATCH `/api/v1/users/{dpi}/2fa`

Activa o desactiva el doble factor de autenticación de un usuario.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Path Parameters

| Parámetro | Tipo   | Descripción    |
|-----------|--------|----------------|
| `dpi`     | String | DPI del usuario |

### Request Body

```json
{
  "enabled": true
}
```

| Campo     | Tipo    | Obligatorio | Descripción                             |
|-----------|---------|-------------|-----------------------------------------|
| `enabled` | Boolean | ✅ Sí        | `true` para habilitar, `false` para deshabilitar |

**El parámetro "true" puede ser "false" para desactivarlo**

### Response `204 No Content`

> No retorna cuerpo. El estado del 2FA fue actualizado correctamente.
