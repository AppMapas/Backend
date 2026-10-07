# Guía de lectura del código de Agenda y Google Calendar

Esta guía explica la estructura de la implementación y las decisiones que conviene conservar al modificarla. Complementa `Agenda_Calendar.md`, que documenta el uso y la configuración.

## 1. Migraciones: por qué se conservan los archivos originales

Flyway registra el checksum de cada migración aplicada. Los archivos V11 y V12 pueden estar instalados en bases existentes: cambiar su contenido, incluso solo para comentar o reformatear, puede provocar que falle la validación al iniciar el backend. Esta guía incorpora la explicación de cada bloque sin cambiar sus archivos. Un cambio futuro de estructura debe escribirse en una nueva migración; no se debe ejecutar `repair` para ocultar una modificación accidental.

### V11: agenda interna, autorización y publicación

Archivo: `src/main/resources/db/migration/V14__agenda_and_google_calendar.sql`.

1. **Control de registros heredados (`DO ... IF EXISTS`).** La migración se detiene si la antigua agenda tiene registros. Esos datos requieren una conciliación de fechas, zona horaria y responsable antes de añadir campos obligatorios. No deben eliminarse para evadir el control.
2. **Ampliación de `legal_process_calendar`.** Permite una actividad sin expediente y agrega cliente, responsable, horario, tipo, estado, autor y fechas de auditoría. `timestamptz` guarda instantes; `time_zone` conserva la zona de interpretación de la actividad.
3. **Restricciones de integridad.** `agenda_dates` exige fin posterior al inicio y duración máxima de 31 días. Los controles de tipo y estado restringen los valores admitidos. `agenda_completion` mantiene coherencia con el campo heredado `is_completed`.
4. **Identidad de la solicitud.** `request_id` es único; `request_hash` representa su contenido. Juntos permiten que un reintento idéntico recupere el resultado sin duplicar la actividad.
5. **Índices de consulta.** `agenda_window`, `agenda_case` y `agenda_responsible` apoyan búsquedas por período, expediente y responsable. El último se limita a actividades programadas.
6. **`agenda_event_history`.** Conserva operadora, acción, versión, motivo y horario. La unicidad de evento y versión impide dos entradas del mismo cambio.
7. **`google_calendar_connection`.** V11 creó una conexión única y una fila vacía inicial. Guarda el token cifrado, su titular, calendario y estado. V12 cambia esta estructura a conexiones individuales.
8. **`google_calendar_oauth_intent`.** Guarda la huella del `state`, su titular y vencimiento. El valor original se entrega al navegador y se consume una sola vez al conectar.
9. **`agenda_google_event`.** Relaciona la actividad interna con el evento de Google por calendario. `etag` detecta cambios externos y `synced_version` identifica la última versión publicada.
10. **`agenda_sync_outbox`.** Guarda el trabajo de publicación en la misma transacción que el cambio local. `attempts`, `next_attempt_at` y `last_error` controlan reintentos; `completed_at` marca trabajos terminados o superados.

Ejemplo de lectura de las restricciones existentes, sin constituir una nueva migración:

```sql
-- La actividad debe tener una duración positiva y no exceder 31 días.
CHECK (
    ends_at > starts_at
    AND ends_at <= starts_at + INTERVAL '31 days'
);

-- La publicación de una versión se encola una sola vez.
UNIQUE (event_id, event_version);
```

### V12: conexiones individuales, series y excepciones

Archivo: `src/main/resources/db/migration/V15__agenda_individual_connections_and_recurrence.sql`.

1. **Retirar solo la conexión inicial vacía.** El `DELETE` exige titular y token nulos; conserva una conexión existente con credenciales.
2. **Retirar el límite `id = 1`.** El bloque `DO` localiza esa restricción por su definición. Se pasa a identificadores `bigint` con identidad y se avanza la secuencia por encima de los IDs existentes.
3. **Una conexión por usuaria.** `owner_dpi` pasa a ser obligatorio y único. El índice parcial de `google_subject` impide que una misma identidad Google esté activa en dos cuentas internas simultáneamente.
4. **Vincular los trabajos a su conexión.** Se agrega `connection_id` al outbox. Los trabajos antiguos pendientes se asignan a la conexión heredada que permanece; si no hay conexión quedan sin asignar hasta una autorización posterior.
5. **Definir la recurrencia.** `recurrence_frequency` y `recurrence_until` deben estar ambos ausentes o contener una frecuencia admitida y su fecha de fin. La validación del horizonte de 366 días también se realiza en Java.
6. **Separar revisiones.** `version` detecta cualquier cambio de la actividad; `master_revision` identifica cambios de la serie principal. La migración conserva las versiones existentes para que una actualización posterior se publique correctamente.
7. **Registrar la ocurrencia en el historial.** `original_starts_at` identifica la fecha original de una ocurrencia, incluso cuando posteriormente se reprograma.
8. **`agenda_event_exception`.** Guarda únicamente ocurrencias modificadas. La clave `(event_id, original_starts_at)` mantiene una identidad estable. `revision` y `synced_revision` evitan publicar excepciones sin cambios.
9. **`agenda_google_audit`.** Registra las operaciones sobre eventos externos con su operadora, calendario, evento, acción y fecha. No almacena tokens.
10. **Índices adicionales.** Facilitan consultas por horario de excepción y la selección de trabajos pendientes de una conexión.

Ejemplo de lectura de la identidad de una ocurrencia:

```sql
-- Reprogramar una ocurrencia modifica starts_at, pero conserva original_starts_at.
PRIMARY KEY (event_id, original_starts_at);

-- Solo las identidades Google conectadas participan en esta unicidad.
CREATE UNIQUE INDEX agenda_connected_google_account
    ON google_calendar_connection (google_subject)
    WHERE google_subject IS NOT NULL
      AND state <> 'DISCONNECTED';
```

## 2. Orden recomendado para leer el backend

| Archivo | Responsabilidad |
| --- | --- |
| `AgendaController` | Rutas HTTP, validación de contratos y respuestas sin caché. |
| `AgendaDtos` | Solicitudes y respuestas; versiones, ocurrencias y estados. |
| `AgendaService` | Crear, editar y cambiar estados dentro de una transacción. |
| `AgendaRules` | Reglas de fechas, duración y transiciones. |
| `AgendaRecurrence` | Expansión finita de series y construcción de RRULE. |
| `AgendaRepository` | Consultas parametrizadas, excepciones, historial y outbox. |
| `GoogleConnectionService` | OAuth por usuaria, conexión, revocación y resolución de conflictos. |
| `GoogleTokenCipher` | Cifrado autenticado de las credenciales de renovación. |
| `GoogleCalendarProperties` | Validación de configuración antes de habilitar la integración. |
| `AgendaSyncWorker` | Publicación periódica de trabajos pendientes. |
| `GoogleCalendarGateway` | Solicitudes HTTPS y clasificación de errores del proveedor. |
| `GoogleAgendaService` | Consulta y edición directa de eventos externos. |
| `GoogleEventMapper` | Conversión acotada de datos externos para la interfaz. |
| `AgendaConfiguration` | Habilitación de las tareas periódicas. |

### Guardar una actividad

`Controller → Service → Rules y validación de vínculos → Repository → historial y outbox → commit`.

La llamada a Google ocurre después, desde el trabajador periódico. Una indisponibilidad externa no elimina una actividad ya guardada. El bloqueo de escrituras protege la comprobación de horarios y la versión evita sobrescribir una edición concurrente.

### Conectar Google

`begin → state aleatorio y huella con vencimiento → popup Google → connect → consumir state → canjear código → verificar identidad y permisos → cifrar token → guardar conexión`.

El frontend recibe el ID público del cliente OAuth, nunca el secreto ni el token de renovación. El origen y el state son controles complementarios a la sesión autenticada.

### Publicar y resolver discrepancias

El trabajador selecciona conexión y trabajo con `FOR UPDATE SKIP LOCKED`, comprueba que la versión siga vigente y usa un ID externo estable. Publica la serie cuando corresponde y luego sus excepciones pendientes. Los errores temporales generan espera creciente; los conflictos y permisos insuficientes requieren una acción explícita.

`ETag` pertenece a la versión remota de Google. La versión local pertenece a la actividad interna. Ambos controles deben conservarse; uno no sustituye al otro.

## 3. Lectura del frontend

- `pages/AgendaPage.vue`: filtros, vistas, selección y coordinación de modales.
- `stores/agendaStore.js`: carga de lista y calendarios; invalida respuestas antiguas y limpia datos al cambiar de sesión.
- `stores/agendaSubmissionStore.js`: identidad del envío para reintentos sin duplicados.
- `services/agendaApi.js`: rutas sobre el cliente HTTP compartido.
- `domain/agenda.js` y `domain/calendar.js`: fechas, validaciones y preparación de datos para visualizar.
- `AgendaCalendar.vue` y `AgendaWeekView.vue`: vistas de calendario, fin exclusivo y posiciones de actividades coincidentes.
- `AgendaEventForm.vue`: actividades internas, series y ocurrencias.
- `GoogleEventForm.vue`: edición externa con ETag.
- `GoogleCalendarConnection.vue`: carga de Google Identity, preparación y autorización del popup; ignora callbacks de sesiones anteriores.
- `UpcomingActivities.vue`: próximas actividades y protección frente a respuestas antiguas.

Los comentarios explican decisiones y límites. Las llaves hacen visibles los bloques condicionales y bucles; no se utilizan ternarios nuevos. Los contratos HTTP, textos de interacción y reglas de negocio se conservan.
