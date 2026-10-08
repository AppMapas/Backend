# Agenda interna e integración con Google Calendar

Para recorrer la implementación y entender las migraciones V11 y V12, consulta [la guía de lectura del código](Agenda_Lectura_Codigo.md).

## Alcance y flujo

**Agenda** (`/agenda`) reúne actividades internas de la oficina y eventos del calendario de Google configurado. La oficina mantiene sus registros en PostgreSQL; Google es opcional y permite consultar o editar el calendario compartido desde la aplicación y desde Calendar.

1. La abogada consulta Mes o Semana, selecciona una fecha y revisa la lista paginada de 25 actividades y las próximas 10 de los siguientes 30 días.
2. Registra una cita, audiencia, presentación, entrega, seguimiento o recordatorio de cobro. Cita exige cliente o expediente; audiencia y cobro requieren expediente activo.
3. Puede repetir diariamente, semanalmente, mensualmente o anualmente hasta una fecha fin. Se almacena una serie, no cientos de filas independientes.
4. Guarda el registro, su historial y el trabajo de publicación en una sola transacción. Google se procesa después, por la conexión de la operadora que hizo el cambio. La agenda interna sigue funcionando sin Google.
5. Cada usuaria conecta su propia cuenta mediante OAuth. Todas las cuentas autorizadas deben editar el mismo calendario privado del despacho, configurado en el servidor.
6. Los eventos creados directamente en ese calendario de Google aparecen al abrir o actualizar Agenda, y durante la actualización cada 60 segundos con la pantalla visible y sin un editor abierto. No se importan como expedientes o actividades locales automáticamente.
7. Un evento exclusivo de Google se edita en su propio formulario. Una actividad enlazada se edita desde su registro interno para conservar cliente, expediente e historial.
8. Si los horarios difieren, se elige explícitamente **Conservar horario de la aplicación** o **Aplicar horario de Google**. La aplicación valida disponibilidad, versión y fechas antes de aceptar horas externas; conserva títulos y notas privados.

No se implementan invitados, notificaciones por correo, webhooks, cambios automáticos de etapas, cobros o documentos. Los eventos que ya tengan invitados se muestran de solo lectura y se gestionan desde Google Calendar. El formulario público de citas continúa fuera de esta integración.

## Datos y migración

V11 incorpora agenda, historial, correspondencias de Google, estado OAuth y cola persistente. V12 amplía ese modelo:

| Tabla | Responsabilidad |
| --- | --- |
| `legal_process_calendar` | Actividad o serie, zona, responsable, cliente/expediente, estado, versión y revisión del evento principal. |
| `agenda_event_history` | Operadora, acción, motivo, horario y ocurrencia original si corresponde. |
| `google_calendar_connection` | Una conexión cifrada por usuaria; se retira la restricción anterior `id=1`. |
| `google_calendar_oauth_intent` | Estado aleatorio hash, titular, consumo único y vencimiento en 10 minutos. |
| `agenda_google_event` | Relación local/Google por calendario, ETag y revisiones publicadas. |
| `agenda_sync_outbox` | Trabajos por versión, conexión asignada, reintentos y errores sanitizados. |
| `agenda_event_exception` | Cambios, cancelaciones y finalizaciones de una ocurrencia, sin alterar el resto. |
| `agenda_google_audit` | Operadora, calendario e ID de los eventos externos editados, sin copiar secretos ni datos del proveedor. |

V12 conserva la conexión anterior y su token cifrado cuando existe una titular. Elimina únicamente la fila inicial vacía, asigna los trabajos pendientes antiguos a la conexión existente y conserva las correspondencias. Las nuevas conexiones reciben un ID propio. `owner_dpi` es único; el identificador verificado de Google no puede estar activo en dos cuentas internas a la vez. La clave de cifrado y su versión deben conservarse al actualizar.

V11 detiene la migración si hay eventos heredados anteriores a Agenda sin horarios y responsable definidos. Conciliar esos datos con una migración revisada antes de desplegar; no borrar registros ni modificar migraciones aplicadas para omitir el control. V12 no cambia V11 ni los contratos de clientes, etapas, pagos o documentos.

## Recurrencias

`recurrence` recibe `{frequency,until}`: `DAILY`, `WEEKLY`, `MONTHLY` o `YEARLY` y fecha local ISO. El fin es obligatorio y no puede exceder 366 días desde el inicio. Se mantiene la hora local de la zona, incluidos sus cambios de horario. Las horas inexistentes durante un cambio horario se omiten. Los eventos con hora conservan duración exacta; los de todo el día conservan días locales y usan UNTIL de fecha. Los meses sin el día solicitado lo omiten; el día 31 no se convierte en el último día del mes. El 29 de febrero se omite en años no bisiestos. La duración no puede generar solapamientos entre ocurrencias.

El listado y `/calendar` expanden la serie en el rango consultado. `originalStartsAt` identifica de manera estable una ocurrencia incluso después de moverla. La edición de una ocurrencia conserva tipo, cliente, expediente, zona y modalidad de todo el día; modifica horario, título, notas o ubicación. Se rechazan solapamientos con otras ocurrencias. Su reprogramación queda acotada a 397 días desde el inicio del evento principal.

Editar toda la serie conserva las excepciones. Una regla nueva que elimine una ocurrencia con historial o excepciones se rechaza para evitar perder datos. Una serie iniciada puede editarse conservando su horario inicial; para cambiar únicamente una fecha futura se edita esa ocurrencia. **Realizada** se aplica a una ocurrencia; **Cancelada** puede aplicarse a una ocurrencia o a toda la serie y exige motivo. No hay borrado físico por API ni edición «esta y las siguientes».

Una regla de Google que no corresponda a las cuatro frecuencias simples puede consultarse y conservarse al editar datos externos. No se transforma silenciosamente. Cambiar la repetición externa a una regla admitida exige un fin acotado. Si una serie enlazada cambia de regla fuera de la app, la conciliación de horas se detiene para que se revise primero su regla.

## Seguridad

- JWT y roles `Abogada`/`Administrador`, con `OfficeAccess` vigente en controlador y servicios. Los registros locales son de la oficina; las credenciales de Google son individuales. Un administrador utiliza su propia conexión, sin sustituir silenciosamente a otra titular.
- El backend deriva operadora, cuenta y calendario. No acepta un calendario arbitrario del navegador. Las correspondencias se consultan en la base; no se confía en `extendedProperties` externas para asignar expedientes.
- Cliente y expediente existentes y activos; coincidencia cliente/expediente. Versiones, UUID de creación y huella normalizada contra reintentos duplicados y cambios concurrentes. Escrituras y revisión de solapamientos protegidas por bloqueo transaccional. Un recordatorio de cobro no bloquea disponibilidad.
- Intervalo positivo, duración máxima de 31 días, medianoche y fin exclusivo para todo el día. Consultas de hasta 93 días; hasta 5.000 eventos principales y 10.000 ocurrencias. Listado local paginado de hasta 100 registros. Google devuelve hasta 250 por página; el frontend limita a 40 páginas y detecta cursores repetidos.
- OAuth por popup con origen exacto, cabecera `X-Requested-With`, JWT y estado hash de consumo único. Se verifica identidad/correo, scopes y permiso de edición del calendario. Una desconexión concurrente invalida una autorización anterior.
- Refresh token cifrado con AES-256-GCM, nonce aleatorio y datos autenticados de titular, cuenta y versión de clave. Client secret y claves únicamente en servidor; nunca en variables VITE, DTO o logs. Access token solo durante la operación.
- ETag/`If-Match` en cambios externos. Una respuesta de publicación perdida se reconoce solo si el evento coincide; un cambio externo distinto queda como conflicto. PATCH conserva los campos de Google que la aplicación no administra. No se agregan, eliminan o notifican invitados desde esta entrega.
- URLs de proveedor fijas, redirecciones HTTP desactivadas, timeouts, IDs/cursor acotados y enlaces limitados a `https://calendar.google.com`. Texto renderizado por Vue sin HTML del proveedor. Respuestas privadas `Cache-Control: no-store`.
- 8 solicitudes externas simultáneas y 60 operaciones de consulta/edición Google por usuaria/minuto **por instancia**. Si se despliegan varias réplicas, aplicar también límite distribuido en gateway. La infraestructura debe mantener HTTPS, secretos protegidos, backups y una política CSP que permita únicamente los recursos requeridos.
- Cierre de sesión limpia agenda, intentos y editores; respuestas tardías de una sesión anterior se invalidan. Google y agenda local cargan independientemente. Un error de autorización Google pide reconectar; no cierra la sesión JWT de la aplicación.

## API: `/api/v1/agenda`

| Método | Ruta | Uso |
| --- | --- | --- |
| GET | `/events?from=&to=&page=0&size=25` | Listado de ocurrencias, filtros `caseId`, `clientDpi`, `status`. |
| GET | `/calendar?from=&to=` | Ocurrencias completas del rango y mismos filtros. |
| GET | `/days?from=&to=` | Contadores diarios independientes de la página. |
| GET | `/upcoming?caseId=&clientDpi=` | Primeras 10 actividades locales programadas, siguientes 30 días. |
| GET | `/events/{id}` | Evento principal actualizado. |
| GET | `/events/{id}/history` | Historial, más reciente primero. |
| POST | `/events` | `requestId`; 201 o 200 al reintentar, `Idempotency-Replayed`. |
| PUT | `/events/{id}` | Editar actividad o toda la serie con `version`. |
| POST | `/events/{id}/status` | `{status,version,reason}`. |
| GET | `/events/{id}/occurrence?originalStartsAt=` | Consultar ocurrencia por fecha original. |
| PUT | `/events/{id}/occurrence` | `{originalStartsAt,event:{...campos de actividad,version}}`. |
| POST | `/events/{id}/occurrence/status` | `{originalStartsAt,change:{status,version,reason}}`. |
| GET | `/google/status` | Estado/correo/pendientes de la usuaria actual, nunca tokens. |
| POST | `/google/intent` | Preparar estado OAuth. |
| POST | `/google/connect` | `{code,state}`, exige origen/cabecera. |
| DELETE | `/google/connect` | Desconectar y revocar solo la cuenta propia. |
| POST | `/google/retry` | Reintentos recuperables de la conexión propia. |
| GET | `/google/events?from=&to=&pageToken=` | Eventos externos y enlazados, próxima página, hora de consulta. |
| GET | `/google/events/{eventId}` | Evento o principal de una serie Google. |
| PUT | `/google/events/{eventId}` | Evento externo: título, descripción pública, horas, zona, todo el día, `etag`, `scope: ONE/ALL`, repetición opcional. `clearRecurrence:true` retira explícitamente repetición en ALL. |
| POST | `/events/{id}/google/assign` | `{version}`: publicar un trabajo pendiente con la cuenta actual, registrado en historial. |
| POST | `/events/{id}/google/reconcile` | `{version,useAppSchedule,originalStartsAt?}`: conservar horas internas o aplicar externas. |

Errores: 400 datos inválidos; 401 JWT inválido; 403 perfil/origen; 404 inexistente; 409 versión, horario, conexión o ETag; 429 límites; 503 fallo temporal Google. Los detalles sensibles del proveedor no se retornan.

Ejemplo de actividad recurrente:

```json
{
  "requestId": "5d62c330-00b9-4822-8487-f393ff169891",
  "title": "Consulta inicial",
  "description": "Notas privadas del despacho",
  "type": "APPOINTMENT",
  "startsAt": "2027-01-12T15:00:00Z",
  "endsAt": "2027-01-12T16:00:00Z",
  "timeZone": "America/Guatemala",
  "allDay": false,
  "clientDpi": "1000000000001",
  "recurrence": {"frequency":"WEEKLY","until":"2027-04-12"}
}
```

## Sincronización y recuperación

El trabajador atiende hasta 10 trabajos cada 30 segundos. Bloquea conexión/trabajo y usa `SKIP LOCKED`; comprueba que la titular conserve rol de oficina y permiso de edición Google. Cada evento principal tiene ID estable por calendario; se publica una sola serie y luego únicamente las excepciones modificadas. Versiones anteriores quedan superadas. Un cambio local reasigna el nuevo trabajo a la conexión de la operadora actual si está conectada; no utiliza credenciales ajenas como respaldo.

Si una usuaria guarda sin conexión, el registro local permanece; al conectar se asignan sus trabajos pendientes. **Publicar pendiente con mi cuenta** permite una reasignación explícita y auditada. Desconectar conserva eventos y trabajos, que quedan pausados; no impide que otra usuaria gestione el calendario con su propia autorización. No genera otra copia de un evento que ya está publicado.

Fallos temporales tienen espera creciente con variación, hasta 8 intentos. Credencial revocada exige reconexión; falta de permiso exige corregir acceso. Los conflictos no se reintentan a ciegas. Resolver un conflicto registra otra versión y agenda una publicación con el ETag vigente. **Aplicar horario de Google** copia únicamente horas válidas, después de comprobar cliente/expediente y disponibilidad; no incorpora descripción pública como notas privadas. Un principal borrado se puede recrear explícitamente al conservar datos internos, reseteando correspondencias de sus excepciones.

Lectura externa directa, sin copia masiva ni webhooks: la app depende de conexión/permiso/cuota para mostrar eventos exclusivos de Google. La hora de última consulta y los toasts indican fallos. Un calendario público o información sensible escrita manualmente en un evento Google quedan fuera del control de privacidad de la app. Cambiar `GOOGLE_CALENDAR_ID` de una cuenta ya conectada exige desconectar y reconciliar operativamente sus publicaciones; no se migra de destino silenciosamente.

## Configuración de Google: obtener credenciales y autorizar tokens

**No se generan ni se copian access tokens o refresh tokens manualmente.** En Google Cloud se obtiene un cliente OAuth; después la licenciada autoriza desde Agenda y el backend obtiene, cifra y renueva los tokens.

1. Abrir https://console.cloud.google.com con la cuenta administradora del proyecto. Crear o seleccionar un proyecto y habilitar **Google Calendar API** en APIs y servicios → Biblioteca.
2. Configurar **Google Auth Platform**: nombre, soporte, audiencia y datos de contacto. Para Gmail personal, audiencia externa; para una organización Workspace, evaluar audiencia interna.
3. En Acceso a datos solicitar `openid`, `email` y `https://www.googleapis.com/auth/calendar.events`. Este último permite eventos de los calendarios accesibles; el backend restringe operaciones al calendario configurado. No solicitar Gmail, contactos, Drive ni administración de permisos.
4. En modo Testing agregar la cuenta de la licenciada como usuaria de prueba. Los refresh tokens externos con Calendar pueden vencer a los 7 días. Antes del uso estable configurar publicación y verificación cuando corresponda. Publicar no garantiza credenciales perpetuas; revocación y políticas administrativas también pueden invalidarlas.
5. Crear **Cliente OAuth → Aplicación web**. Registrar orígenes JavaScript exactos, por ejemplo `http://localhost:5173` y el origen HTTPS de producción. Este flujo utiliza popup GIS; no usa un callback por redirección. Si se cambia de flujo, registrar el URI correspondiente y adaptar protección/canje.
6. Copiar **Client ID** y **Client Secret** al entorno privado del backend. Nunca pegar el secreto en el frontend ni en el repositorio.
7. En https://calendar.google.com crear un calendario dedicado al despacho. Obtener su ID en Configuración del calendario → Integrar el calendario → ID del calendario. La cuenta que autoriza debe ser propietaria o tener permiso de editar eventos. El calendario debe mantenerse privado.
8. Generar una clave de cifrado independiente de la del JWT:

   ```bash
   openssl rand -base64 32
   ```

   Guardarla mediante el gestor de secretos o entorno privado del backend. No enviar el resultado a chats ni versionarlo.
9. Configurar el entorno del backend:

   ```properties
   GOOGLE_CALENDAR_ENABLED=true
   GOOGLE_CLIENT_ID=CLIENT_ID_DE_APLICACION_WEB
   GOOGLE_CLIENT_SECRET=SECRETO_OAUTH
   GOOGLE_CALENDAR_ID=ID_DEL_CALENDARIO_DE_OFICINA
   GOOGLE_FRONTEND_ORIGIN=http://localhost:5173
   FRONTEND_ALLOWED_ORIGINS=http://localhost:5173
   GOOGLE_TOKEN_ENCRYPTION_KEY=CLAVE_BASE64_DE_32_BYTES
   GOOGLE_TOKEN_KEY_VERSION=v1
   GOOGLE_SYNC_DELAY_MS=30000
   ```

   En producción utilizar el origen HTTPS real en ambas variables de origen. CORS conserva `allowCredentials=false`: la sesión usa Bearer JWT. No se requiere una variable VITE de Google; el ID público se entrega mediante `/google/status`. El rol IAM de S3 para documentos no reemplaza este cliente OAuth.
10. En la aplicación abrir **Agenda → Conectar Google → Autorizar Google**. La preparación precede al segundo clic para mantener la apertura del popup asociada a una interacción del usuario. Cada usuaria selecciona su propia cuenta de Google con permiso de edición del calendario compartido y concede permisos. Una misma cuenta de Google no se conecta simultáneamente a dos usuarias internas. Verificar el estado conectado y el calendario destino.
11. Registrar una actividad de prueba sin información sensible. Esperar el ciclo de sincronización, actualizar la agenda y comprobar el evento en Google. Esta validación real requiere credenciales y consentimiento del usuario; las pruebas automatizadas simulan Google.

Si no se configura Google o la configuración está incompleta, la agenda interna permanece disponible. El hosting debe permitir GIS en las directivas CSP necesarias para `https://accounts.google.com`; conservar los dominios y recursos de la aplicación al adaptar la política. No agregar comodines indiscriminados.

### Renovación, cambio de cuenta y clave

- Si Google no entrega un refresh token y no existe uno válido de la misma cuenta, retirar el permiso de la app en la cuenta Google y autorizar otra vez.
- Para cambiar de cuenta, desconectar primero; la nueva cuenta también debe editar el mismo calendario.
- Para rotar clave: con la clave anterior disponible, desconectar y revocar; actualizar clave y `GOOGLE_TOKEN_KEY_VERSION`; volver a autorizar. No reemplazar la clave y asumir que los tokens anteriores podrán descifrarse. El módulo exige reconexión y no incluye un almacén de múltiples claves.
- Respaldar la base y gestionar la clave separadamente; pérdida de clave implica reconectar. Proteger el acceso al gestor de secretos y la cuenta Google con los controles de la organización.

## Pruebas sin API ni Docker

```bash
mvn -o test
mvn -o -Dagenda.integration=true \
  -Dagenda.test.url=jdbc:postgresql://127.0.0.1:55473/agenda_test \
  -Dtest=AgendaPersistenceTest,AgendaMigrationTest test
```

Las pruebas PostgreSQL requieren una base local desechable `agenda_test` ya disponible; crean y eliminan solo esquemas de prueba. Cubren V1–V12, conservación de la conexión existente, atomicidad, concurrencia de horarios, conexiones independientes, recurrencias, excepciones, cola e historial. Google se simula en pruebas: se cubren ETag, invitados de solo lectura, datos mínimos, identificadores maliciosos y recuperación de respuestas perdidas.

La prueba real de OAuth y publicación requiere credenciales válidas y consentimiento de la licenciada. No se inició la API ni Docker durante la implementación. Frontend exige Node `^22.18.0` o `>=24.12.0`; su documento incluye pruebas de dominio, stores y navegador.

Referencias: [GIS código](https://developers.google.com/identity/oauth2/web/guides/use-code-model), [Eventos recurrentes](https://developers.google.com/workspace/calendar/api/guides/recurringevents), [PATCH](https://developers.google.com/workspace/calendar/api/v3/reference/events/patch), [iCalendar RFC 5545](https://www.rfc-editor.org/rfc/rfc5545), [Scopes](https://developers.google.com/workspace/calendar/api/auth), [Tokens OAuth](https://developers.google.com/identity/protocols/oauth2).
