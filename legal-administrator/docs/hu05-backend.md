# HU-05: clientes y apertura de expedientes

## Alcance

Se implementan los datos personales del cliente, su alta/edición/desactivación,
la apertura de expedientes desde plantillas publicadas, la copia de requisitos
y la recuperación mediante búsquedas paginadas.

Esta entrega corresponde al backend. El listado responsivo y los formularios
móviles deben consumir estos contratos desde el frontend. No incorpora carga,
almacenamiento ni descarga de archivos, pagos o transiciones de etapas.
`requiresDocument` indica un requisito documental, no la existencia de un archivo.

## Flujo

1. Iniciar sesión como **Abogada** o **Administrador**.
2. Consultar los catálogos y `GET /api/v1/process-types?status=PUBLISHED`.
   Consultar el detalle de la plantilla para presentar sus requisitos y conservar
   su `version`.
3. Buscar un cliente existente o capturar los datos de uno nuevo.
4. Enviar `POST /api/v1/legal-processes` con un UUID `requestId`, la plantilla
   y su versión. Indicar **solamente uno** de `clientDpi` o `client`.
5. El servidor comprueba permisos vigentes, datos personales, actividad del
   cliente, publicación y versión de la plantilla y actividad de sus requisitos.
6. En una transacción se guardan el cliente nuevo, si corresponde, el expediente
   y las copias ordenadas de sus requisitos. Un fallo revierte todas esas escrituras.
7. Consultar el detalle y los listados paginados. El expediente conserva la
   configuración utilizada al abrirlo aunque posteriormente cambie el catálogo.

Un cliente puede tener varios expedientes, incluso del mismo tipo. No se impone
una unicidad cliente/tipo que impediría casos legales legítimos.

## Datos personales

Obligatorios: DPI, nombres, apellidos, correo, teléfono, nacionalidad, estado civil
y dirección exacta. Opcionales: fecha de nacimiento, ocupación y municipio.

- DPI: cadena de **13 dígitos ASCII**, conserva ceros iniciales. Se valida su formato,
  no su identidad ni su autenticidad ante RENAP.
- DPI único en la base de datos y no editable mediante este contrato.
- Correo válido de hasta 100 caracteres.
- Teléfono: entre 8 y 12 dígitos, con `+` inicial opcional.
- Nombres/apellidos: hasta 100 caracteres; dirección: hasta 255; ocupación: hasta 150.
- Fecha de nacimiento anterior al día actual, si se proporciona.
- Identificadores de catálogos positivos y existentes.
- Se normalizan espacios exteriores y Unicode; se rechazan caracteres de control
  salvo saltos de línea en texto. Las observaciones admiten hasta 5000 caracteres.

Los textos son datos planos. El frontend debe renderizarlos con interpolación
de Vue, evitando introducirlos en `v-html`.

## Endpoints

Todos los siguientes requieren `Authorization: Bearer <accessToken>` y permisos
de Abogada/Administrador.

| Método | Ruta | Función |
| --- | --- | --- |
| POST | /api/v1/clients | Crear cliente sin abrir un expediente |
| GET | /api/v1/clients | Listado anterior como array, conservado por compatibilidad |
| GET | /api/v1/clients/search | Buscar clientes con paginación |
| GET | /api/v1/clients/{dpi} | Consultar cliente |
| PUT | /api/v1/clients/{dpi} | Actualizar datos personales con versión |
| PATCH | /api/v1/clients/{dpi}/deactivate | Desactivar con `{"version":0}` |
| DELETE | /api/v1/clients/{dpi}?version=0 | Alias compatible de ruta, ahora desactivación lógica |
| GET | /api/v1/catalogs/countries | Nacionalidades |
| GET | /api/v1/catalogs/marital-statuses | Estados civiles |
| GET | /api/v1/catalogs/municipalities | Municipios y código de departamento |
| GET | /api/v1/process-types?status=PUBLISHED | Plantillas disponibles |
| POST | /api/v1/legal-processes | Apertura atómica de expediente |
| GET | /api/v1/legal-processes/{id} | Detalle y copias de requisitos |
| PUT | /api/v1/legal-processes/{id} | Editar observaciones con versión |
| GET | /api/v1/legal-processes | Buscar expedientes con paginación |

### Crear cliente y expediente

Los IDs de catálogo y plantilla son ejemplos; consultar los catálogos reales.

```json
{
  "requestId": "27d3bb30-53c5-4354-8a80-60f52237c9e8",
  "processTypeId": 1,
  "processTypeVersion": 3,
  "generalDetails": "Solicitud de memorial",
  "client": {
    "dpi": "1234567890123",
    "firstName": "Ana María",
    "lastName": "López Pérez",
    "email": "ana@example.com",
    "phone": "+50255551234",
    "birthDate": "1990-05-20",
    "maritalStatusId": 1,
    "nationalityId": 1,
    "occupation": "Comerciante",
    "exactAddress": "Zona 1, Quetzaltenango",
    "municipalityId": 1
  }
}
```

Para un cliente existente, sustituir `client` por
`"clientDpi": "1234567890123"`. No enviar ambos campos.

La respuesta contiene `caseData` y `requirements`. Incluye el código
`EXP-<consecutivo>`, la versión, el estado inicial `OPEN`, la responsable
y las copias de requisitos en estado `PENDING`.

La responsable y creadora se obtienen de la sesión. El cuerpo no permite fijar
responsable, estado, código de expediente ni las copias históricas.

### Edición

Para editar el cliente, enviar sus datos personales obligatorios y la `version`
obtenida en la consulta. El DPI se toma de la ruta.

Para editar las observaciones:

```json
{"version": 0, "generalDetails": "Observación actualizada"}
```

La versión cambia cuando cambian los datos. Si no cambian, puede mantenerse.
No se permite cambiar el cliente ni la plantilla de un expediente.
La desactivación de clientes conserva sus expedientes y evita nuevas aperturas.

### Búsqueda

- Clientes: `/api/v1/clients/search?q=Ana%20Pérez&active=true&page=0&size=20`.
- Expedientes: `/api/v1/legal-processes?q=EXP-1&active=true&page=0&size=20`.
- Filtros adicionales de expedientes: `clientDpi`, `processTypeId`, `status`.
- `active=true` es el valor predeterminado para expedientes.
- Página desde 0; tamaño entre 1 y 100; búsqueda de hasta 100 caracteres.
- Búsqueda por fragmentos de nombre/apellido/DPI y, en expedientes, código.
  Varias palabras se buscan conjuntamente, sin distinguir mayúsculas.
  `%` y `_` se tratan literalmente.
- Respuesta: `content`, `page`, `size`, `totalElements`, `totalPages`.
- Orden estable para evitar cambios arbitrarios entre páginas.

Los índices apoyan filtros y ordenación. La búsqueda por fragmentos sigue
requiriendo explorar coincidencias; si aumenta considerablemente el volumen,
medir consultas reales y evaluar índices trigram con una consulta compatible.
El frontend debe aplicar debounce y cancelar respuestas de búsquedas anteriores.

## Reintentos y concurrencia

Generar un UUID para cada intención de apertura. **Conservar el mismo UUID y
el mismo cuerpo si la red falla o el resultado es incierto**. No generar otro
UUID automáticamente para reintentar.

- Primera apertura: HTTP **201**, `Idempotency-Replayed: false`.
- Mismo UUID, usuario y contenido: HTTP **200**, `Idempotency-Replayed: true`;
  se devuelve el mismo expediente, en su estado actual.
- Mismo UUID con contenido diferente o con otra usuaria: HTTP **409**.
- Para una apertura distinta, generar otro UUID.

Se usa un bloqueo transaccional de PostgreSQL por clave de solicitud, una
restricción única en `request_id` y una huella SHA-256 del contenido.
Las colisiones del identificador interno de bloqueo solo serializan solicitudes.
La plantilla y el cliente se bloquean durante la apertura. Los requisitos se
bloquean en orden estable y se leen nuevamente antes de copiarlos.

Las ediciones usan `@Version` para impedir sobrescrituras de datos obsoletos.
El consecutivo de expediente proviene de una secuencia: puede tener saltos tras
una transacción fallida y no debe interpretarse como un contador de casos.

## Seguridad y errores

- Las rutas están protegidas por Spring Security. Los servicios vuelven a comprobar
  el rol vigente en la base para bloquear tokens cuyo rol haya sido revocado.
- Tokens ausentes, inválidos o de renovación no autentican estas operaciones.
- La oficina comparte clientes y expedientes entre Abogada/Administrador.
  Esta política **no implementa aislamiento entre múltiples despachos**.
- Se utilizan DTOs explícitos, validación en controlador y servicio, consultas
  parametrizadas, claves foráneas y restricciones de unicidad.
- La eliminación es lógica; se mantienen las relaciones históricas.
- Las respuestas protegidas incluyen encabezados que impiden almacenamiento
  en caché. Los errores inesperados no exponen SQL, credenciales ni trazas.
- CORS usa orígenes explícitos mediante `FRONTEND_ALLOWED_ORIGINS`, separados por
  comas. Valor predeterminado: localhost/127.0.0.1 en el puerto 5173.
- La API es stateless y usa Bearer en cabecera. CSRF está deshabilitado para ese
  esquema; si se migra la autenticación a cookies, revisar esa configuración.

| HTTP | Tratamiento esperado |
| --- | --- |
| 400 | Corregir datos, JSON o filtros; revisar `details` cuando se incluya |
| 401 | Renovar sesión o iniciar sesión |
| 403 | No mostrar acciones que el perfil no puede realizar |
| 404 | Recargar selección de cliente, plantilla o expediente |
| 409 | Revisar duplicidad, actividad o versiones; recargar antes de reenviar |
| 500 | Mostrar mensaje neutral; para apertura incierta reintentar con el mismo UUID |

En producción, TLS, acceso restringido a PostgreSQL, gestión de secretos,
cuentas iniciales y respaldos deben configurarse en el despliegue. Estos controles
de aplicación no constituyen una garantía de seguridad absoluta.

## Migración V7 y compatibilidad

- Extiende `client_user` y `legal_process`; reutiliza los catálogos y las tablas V6.
  No cambia V1–V6.
- Las nacionalidades, estados civiles y direcciones antiguas quedan sin inventar.
  Se exige completarlas antes de abrir un expediente nuevo.
- La restricción de formato DPI usa `NOT VALID`: conserva registros heredados,
  pero valida nuevas escrituras. Un DPI histórico inválido requiere una corrección
  supervisada; este CRUD no cambia claves primarias.
- Los expedientes históricos conservan códigos y estados. Sus fechas antiguas se
  trasladan a medianoche de Guatemala, dado que no almacenaban hora.
- No se inventan requisitos retrospectivos para expedientes anteriores.
- La secuencia empieza después del mayor código numérico `EXP-` existente.
- Las nuevas altas/ediciones exigen campos que antes no existían. Los formularios
  de alta de clientes existentes deben incorporar estos campos.
- PUT exige `version`; DELETE exige `version` y desactiva en lugar de borrar.
  Actualizar consumidores de esos contratos antes de utilizarlos.

Flyway aplica V7 en el siguiente arranque autorizado del backend. Esta tarea no
aplica la migración a la base de desarrollo o producción ni levanta la API.

## Pruebas sin Docker ni servidor HTTP

```bash
mvn test
```

Incluye validaciones, servicio de clientes y pruebas HTTP con MockMvc.
Mockito se carga como agente por Surefire para evitar su auto conexión en JDK
con restricciones. Las pruebas que requieren PostgreSQL se omiten por defecto.

Para comprobar migraciones, JPA, permisos vigentes, búsquedas, copias históricas,
rollback y reintentos simultáneos, usar **exclusivamente una base local desechable**
llamada `hu05_test`, con usuario que permita crear esquemas y tablas:

```bash
mvn test \
  -Dhu05.integration=true \
  -Dhu05.test.url=jdbc:postgresql://127.0.0.1:55439/hu05_test \
  -Dhu05.test.user=krm
```

Las pruebas no leen la conexión de `.env`; validan que la URL apunte al nombre
y host indicados. Ejecutan las migraciones y escriben datos de prueba. El esquema
`hu05_upgrade` se crea y elimina para comprobar la actualización desde V6.
La conexión de esta prueba local usa contraseña vacía y un servidor de pruebas
con autenticación local configurada por su operador.
