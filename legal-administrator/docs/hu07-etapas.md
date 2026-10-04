# HU-07: seguimiento de expedientes por etapas

## Alcance y conceptos

Las etapas pertenecen a un tipo de trámite. Cada expediente nuevo recibe una
copia de los nombres, el orden y las transiciones permitidas en el momento de
su apertura. Los cambios posteriores en la plantilla no alteran esa copia.

`legal_process.current_status` sigue siendo el estado administrativo del
expediente. La fase de trabajo se consulta en `currentStageId`,
`currentStageCode` y `currentStageName`. Llegar a «Entregado» no desactiva
ni oculta automáticamente el expediente.

No se suben, almacenan ni validan documentos con esta historia. Las etapas y
los requisitos del expediente son conceptos distintos. La tabla antigua
`legal_process_step` permanece sin reinterpretar ni eliminar.

## Configuración de la plantilla

La migración V8 añade cinco tablas: `process_type_stage`,
`process_type_stage_transition`, `legal_process_stage`,
`legal_process_stage_transition` y `legal_process_stage_event`. También
añade `legal_process.current_stage_id`, con una llave foránea que exige que
la etapa pertenezca al mismo expediente.

Las plantillas que ya estaban publicadas, y las utilizadas por un expediente
aunque ahora estén inactivas, reciben el flujo predeterminado:

`PRESENTADO → EN_REVISION → APROBADO → ENTREGADO`

La abogada debe revisar esos flujos y adaptar cada plantilla si el trámite
necesita otra secuencia. Los borradores nuevos necesitan configurar sus
etapas antes de publicarse.

`GET /api/v1/process-types/{id}/stages` devuelve la versión de la plantilla,
las etapas y sus transiciones. `PUT` en la misma ruta reemplaza toda la
configuración con la versión consultada:

```json
{
  "version": 3,
  "stages": [
    {"code":"PRESENTADO","name":"Presentado","displayOrder":1,"initial":true,"terminal":false},
    {"code":"REVISION","name":"En revisión","displayOrder":2,"initial":false,"terminal":false},
    {"code":"ENTREGADO","name":"Entregado","displayOrder":3,"initial":false,"terminal":true}
  ],
  "transitions": [
    {"fromCode":"PRESENTADO","toCode":"REVISION"},
    {"fromCode":"REVISION","toCode":"ENTREGADO"}
  ]
}
```

Los códigos son identificadores estables, en mayúsculas, de 2 a 40 caracteres;
los nombres pueden cambiar. El backend exige de 2 a 40 etapas, posiciones
consecutivas, una sola etapa inicial, al menos una final y un camino desde la
inicial hacia cada etapa y desde cada etapa hacia una final. Los retrocesos
se permiten únicamente mediante una transición configurada. Ninguna
transición puede salir de una etapa final. Una versión obsoleta devuelve 409.

Guardar etapas incrementa la versión de la plantilla. La apertura de un
expediente exige la versión vigente: así se copian requisitos y etapas de una
configuración coherente. La interfaz del catálogo deberá consumir este
endpoint antes de permitir publicar borradores nuevos.

## Apertura y consulta

`POST /api/v1/legal-processes` conserva el contrato de HU-05. Dentro de
la misma transacción ahora copia las etapas y transiciones, marca la inicial
y crea el primer evento. Un error revierte toda la apertura.

`GET /api/v1/legal-processes/{id}` agrega `timeline` junto con
`caseData` y `requirements`:

- `timeline.currentStage`: etapa actual, o `null` en un expediente antiguo.
- `timeline.stages`: etapas ordenadas. En expedientes antiguos sin inicializar
  son una vista previa de la plantilla y tienen `id: null`.
- `timeline.allowedNextStageIds`: destinos permitidos desde la etapa actual.
  En casos antiguos sin inicializar está vacío; se elige un código de la
  vista previa y se envía una explicación.
- `timeline.events`: historial ascendente con origen, destino, usuaria,
  fecha del servidor y observación.

Los resúmenes del listado también incluyen identificador, código y nombre
de la etapa actual. La línea de tiempo debe usar `events` para distinguir
visitas repetidas a una etapa; el orden visual no sustituye al historial.

## Cambio de etapa

`POST /api/v1/legal-processes/{id}/stage-transitions`:

```json
{
  "requestId": "27d3bb30-53c5-4354-8a80-60f52237c9e8",
  "version": 1,
  "targetStageId": 42,
  "comment": "Expediente recibido para revisión"
}
```

Se envía exactamente uno de `targetStageId` o `targetStageCode`. Para
expedientes nuevos se usa el identificador de la etapa copiada. La respuesta
es el detalle actualizado y la cabecera `Idempotency-Replayed: false`.

El servidor bloquea el expediente, comprueba que esté activo, su versión,
la pertenencia de la etapa y la transición permitida. Luego actualiza la
etapa y agrega un evento en una sola transacción. La usuaria se obtiene de
la sesión y la fecha la fija el servidor. La observación es opcional en
avances normales y admite hasta 1000 caracteres.

Si la conexión falla después de enviar el cambio, repetir **el mismo cuerpo
y el mismo `requestId`** devuelve el detalle actual con
`Idempotency-Replayed: true`, sin un segundo evento. Reutilizar la clave
para otra intención, incluso con otra usuaria, devuelve 409. La interfaz
debe conservar la clave y el cuerpo mientras el resultado sea incierto.

### Expedientes anteriores

La migración deja `current_stage_id` vacío en casos existentes: no inventa
el progreso ni fechas anteriores. El detalle muestra las etapas actuales de
la plantilla como vista previa. Para inicializar uno de esos expedientes se
envía `targetStageCode` y una observación obligatoria:

```json
{
  "requestId": "747c68df-c646-4f75-a34e-16cce22864f4",
  "version": 0,
  "targetStageCode": "REVISION",
  "comment": "Se confirmó manualmente que el caso está en revisión"
}
```

La configuración se copia en ese momento y el evento registra la fecha real
de la asignación. Después, los avances usan `targetStageId` de esa copia.

## Seguridad y errores

- GET/PUT de configuración de etapas y las rutas de expedientes requieren
  Abogada o Administrador. El servicio comprueba además el rol vigente en
  la base, incluso si el token aún conserva un rol anterior.
- No se aceptan origen, usuaria, fecha ni código de expediente proporcionados
  por el cliente para el evento.
- Las relaciones entre etapas, transiciones y expedientes se comprueban en
  el servicio y mediante llaves foráneas compuestas en PostgreSQL.
- 400: datos incompletos, código inválido o explicación ausente al inicializar.
- 401/403: sesión ausente o falta de permisos.
- 404: expediente o trámite inexistente.
- 409: versión obsoleta, plantilla sin etapas, expediente inactivo, transición
  prohibida o clave de solicitud reutilizada con otros datos.

La API usa Bearer, respuestas protegidas sin caché y CORS con orígenes
explícitos, según la configuración existente de HU-05.

## Comprobación sin levantar servicios

```bash
mvn -o test
```

Las pruebas unitarias y MockMvc cubren el grafo, transiciones válidas,
rechazos, reintentos, inicialización histórica, versiones y permisos HTTP.
La prueba de migración PostgreSQL está disponible únicamente con la base
local desechable `hu05_test` documentada en `docs/hu05-backend.md`.
No debe ejecutarse contra la base de desarrollo o producción. El primer
arranque autorizado del backend aplicará V8 mediante Flyway.
