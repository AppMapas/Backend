# 🔐 Autenticación — `/api/v1/auth`

Módulo encargado del inicio de sesión, manejo de tokens JWT y autenticación de doble factor (2FA) mediante TOTP.

> [!NOTE]
> Todos los endpoints de este módulo son **públicos** (no requieren token Bearer).

---

## POST `/api/v1/auth/login`

Inicia sesión con correo y contraseña. Si el usuario tiene 2FA activo, el token de acceso no es devuelto hasta completar la verificación.

**Autenticación requerida:** ❌ No

### Request Body

```json
{
  "email": "abogada@despacho.com",
  "password": "miContraseña123"
}
```

| Campo      | Tipo   | Obligatorio | Descripción                     |
|------------|--------|-------------|---------------------------------|
| `email`    | String | ✅ Sí        | Correo electrónico del usuario  |
| `password` | String | ✅ Sí        | Contraseña del usuario          |

### Response `200 OK`

```json
{
  "email": "abogada@despacho.com",
  "firstName": "María",
  "lastName": "López",
  "role": "Abogada",
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "twoFactorRequired": false,
  "message": "Inicio de sesión exitoso"
}
```

> [!TIP]
> Si `twoFactorRequired` es `true`, los campos `accessToken` y `refreshToken` estarán vacíos. Debe completar el flujo 2FA con `/api/v1/auth/2fa/verify`.

| Campo               | Tipo    | Descripción                                              |
|---------------------|---------|----------------------------------------------------------|
| `email`             | String  | Correo del usuario autenticado                           |
| `firstName`         | String  | Nombre del usuario                                       |
| `lastName`          | String  | Apellido del usuario                                     |
| `role`              | String  | Rol asignado al usuario                                  |
| `accessToken`       | String  | JWT de acceso (vacío si se requiere 2FA)                 |
| `refreshToken`      | String  | JWT de refresco (vacío si se requiere 2FA)               |
| `twoFactorRequired` | Boolean | Indica si se requiere verificación 2FA                   |
| `message`           | String  | Mensaje descriptivo del resultado                        |

---

## POST `/api/v1/auth/2fa/verify`

Verifica el código TOTP tras un login con 2FA activo. Devuelve los tokens de acceso completos.

**Autenticación requerida:** ❌ No

### Request Body

```json
{
  "email": "abogada@despacho.com",
  "code": "123456"
}
```

| Campo   | Tipo   | Obligatorio | Descripción                                |
|---------|--------|-------------|--------------------------------------------|
| `email` | String | ✅ Sí       | Correo del usuario (si no viene del token) |
| `code`  | String | ✅ Sí       | Código TOTP de 6 dígitos                   |

### Response `200 OK`

```json
{
  "email": "abogada@despacho.com",
  "firstName": "María",
  "lastName": "López",
  "role": "Abogada",
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "twoFactorRequired": false,
  "message": "Verificación 2FA exitosa"
}
```

---

## POST `/api/v1/auth/2fa/setup`

Genera la clave secreta TOTP y el URI del código QR para configurar la aplicación autenticadora del usuario.

**Autenticación requerida:** ❌ No

### Request Body

```json
{
  "email": "abogada@despacho.com"
}
```

| Campo   | Tipo   | Obligatorio | Descripción                    |
|---------|--------|-------------|--------------------------------|
| `email` | String | ✅ Sí        | Correo del usuario a configurar |

### Response `200 OK`

```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "qrCodeUri": "otpauth://totp/GestorJuridico:abogada@despacho.com?secret=JBSWY3DPEHPK3PXP&issuer=GestorJuridico",
  "manualEntryKey": "JBSWY3DPEHPK3PXP"
}
```

| Campo            | Tipo   | Descripción                                               |
|------------------|--------|-----------------------------------------------------------|
| `secret`         | String | Clave secreta TOTP generada                               |
| `qrCodeUri`      | String | URI para generar el código QR en la app autenticadora     |
| `manualEntryKey` | String | Clave para ingreso manual en la aplicación autenticadora  |

---

## POST `/api/v1/auth/2fa/enable`

Habilita el 2FA para un usuario verificando el código TOTP generado por su app autenticadora.

**Autenticación requerida:** ⚠️ Opcional (Bearer JWT)

### Request Body

```json
{
  "email": "abogada@despacho.com",
  "code": "123456"
}
```

| Campo   | Tipo   | Obligatorio | Descripción                                |
|---------|--------|-------------|--------------------------------------------|
| `email` | String | ⚠️ Opcional  | Correo del usuario (si no viene del token) |
| `code`  | String | ✅ Sí        | Código TOTP de 6 dígitos                   |

### Response `200 OK`

```json
{
  "message": "2FA habilitado correctamente",
  "twoFactorEnabled": true
}
```

---

## POST `/api/v1/auth/2fa/disable`

Deshabilita el 2FA para un usuario verificando el código TOTP.

**Autenticación requerida:** ⚠️ Opcional (Bearer JWT)

### Request Body

```json
{
  "email": "abogada@despacho.com",
  "code": "123456"
}
```

| Campo   | Tipo   | Obligatorio | Descripción                                |
|---------|--------|-------------|--------------------------------------------|
| `email` | String | ⚠️ Opcional  | Correo del usuario (si no viene del token) |
| `code`  | String | ✅ Sí        | Código TOTP de 6 dígitos                   |

### Response `200 OK`

```json
{
  "message": "2FA deshabilitado correctamente",
  "twoFactorEnabled": false
}
```

---

## POST `/api/v1/auth/refresh`

Renueva el token de acceso usando el refresh token.

**Autenticación requerida:** ❌ No

### Request Body

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

| Campo          | Tipo   | Obligatorio | Descripción            |
|----------------|--------|-------------|------------------------|
| `refreshToken` | String | ✅ Sí        | Token de refresco JWT  |

### Response `200 OK`

```json
{
  "email": "abogada@despacho.com",
  "firstName": "María",
  "lastName": "López",
  "role": "Abogada",
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "twoFactorRequired": false,
  "message": "Token renovado exitosamente"
}
```
