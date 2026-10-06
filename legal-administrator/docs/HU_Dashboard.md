# Dashboard: resumen operativo del despacho

## Alcance de esta entrega

La pantalla Inicio permite consultar expedientes activos, actividades de hoy,
recordatorios de cobro programados y un gráfico de actividades de los próximos
siete días. Todo lo relacionado con importes, saldos, ingresos, egresos,
comparativos financieros y detección de deudas queda pendiente por solicitud
del usuario. Los módulos existentes de Caja y pagos conservan su funcionamiento.

No se almacenan estadísticas duplicadas: se calculan al consultar las tablas
existentes `legal_process` y `legal_process_calendar`, usando el servicio de
Agenda para expandir recurrencias y aplicar sus excepciones. No requiere una
migración ni nuevas credenciales de Google.

## Endpoints

| Método y ruta | Resultado |
| --- | --- |
| `GET /api/v1/dashboard/summary` | Resumen operativo y vistas previas. |
| `GET /api/v1/dashboard/reminders` | Recordatorios filtrados y paginados. |

El listado admite `kind=TODAY`, `UPCOMING` o `UNATTENDED`; omitirlo consulta
todos. `page` empieza en cero y admite hasta 10000; `size` tiene valor
predeterminado 10 y rango 1–25. Parámetros inválidos devuelven 400. Las respuestas
correctas usan `Cache-Control: no-store`.

El resumen devuelve:

- `generatedAt`, `date` y `timeZone`: momento y fecha de la consulta.
- `activeCases`: conteo de todos los registros con `legal_process.active=true`;
  no se deduce de una página de expedientes ni de un nombre de etapa.
- `todayActivities`: total de actividades internas programadas que coinciden
  con el día; `agenda` muestra como máximo ocho, ordenadas por inicio.
- `reminders`: conteos completos de los tres grupos; `reminderPreview` muestra
  como máximo cinco. La vista previa no determina el conteo.
- `unattendedFrom` y `upcomingThrough`: límites visibles de los recordatorios.
- `week`: siete fechas consecutivas desde hoy con sus conteos internos.

Cada actividad incluye identidad, título, tipo, horario, zona, indicador de todo
el día, referencia del expediente y `originalStartsAt` cuando es una ocurrencia.
No se incluyen DPI, notas privadas, tokens ni campos monetarios. El listado usa
`reminders.content/page/size/totalElements/totalPages`, con los mismos límites
de fecha y una marca temporal propios de su consulta.

## Reglas de fecha y recordatorios

La fecha del despacho se calcula en `America/Guatemala`, usando un `Clock`
inyectable. El final de cada día es exclusivo: una actividad que termina justo
a medianoche no pertenece al día siguiente. Una actividad de varios días cuenta
en cada día que ocupa. El gráfico cuenta actividades por día, no eventos únicos
de toda la semana.

Solo se cuentan actividades `SCHEDULED`. Para los recordatorios se exige además
el tipo `PAYMENT_REMINDER`:

1. `TODAY`: la actividad ocupa parte de hoy.
2. `UNATTENDED`: terminó antes de hoy, continúa programada y empezó dentro de
   los treinta días anteriores. Un recordatorio más antiguo no se incluye.
3. `UPCOMING`: está programada después de hoy, hasta el sexto día siguiente.

El orden del listado prioriza sin atender, hoy y próximos; dentro de cada grupo
ordena por inicio e identificador. Las cancelaciones, finalizaciones y
excepciones de recurrencia se obtienen del flujo existente de Agenda.

**Un recordatorio sin atender no acredita deuda ni pago vencido.** Registrar un
abono no completa automáticamente una actividad de Agenda. La abogada revisa
el expediente y finaliza, cancela o reprograma el recordatorio en Agenda.

## Google Calendar

El backend del dashboard no llama a Google dentro de su transacción. El frontend
consulta después los endpoints autenticados existentes de conexión y eventos.
Los eventos externos de hoy se presentan en una sección independiente; los que
tienen `localEventId` no se vuelven a sumar allí. El conteo diario y el gráfico
son de la agenda interna, sin sumar ni inferir estados de eventos externos.

Si Google no está conectado, está deshabilitado o falla, el resumen local sigue
disponible. Para conectar, editar eventos externos o conciliar cambios enlazados
se utiliza el flujo existente de Agenda.

## Seguridad y coherencia

- JWT y autorización para `Abogada` y `Administrador` en configuración HTTP,
  controlador y servicio. `OfficeAccess` vuelve a comprobar la cuenta y su rol
  vigentes en la base de datos; otro perfil no obtiene datos del dashboard.
- Se conserva el ámbito compartido del despacho que ya aplica Agenda y
  expedientes; no se introduce un filtro por creador que oculte casos existentes.
- Consultas de solo lectura con aislamiento `REPEATABLE_READ` y límite de
  transacción de quince segundos, para una lectura consistente dentro de cada
  respuesta. Dos consultas separadas pueden reflejar cambios intermedios.
- Conteo SQL fijo y parámetros tipados; no recibe expresiones SQL del navegador.
- Ventana acotada de 37 días: los treinta anteriores y los siete desde hoy.
  Se conservan los límites de Agenda de 5000 registros maestros y 10000
  ocurrencias. Si se superan, la consulta falla explícitamente; no se entrega
  un conteo parcial como si fuera completo.
- El frontend conserva datos solo en memoria, cancela consultas anteriores y
  descarta respuestas tardías al cerrar sesión o cambiar de cuenta. Un error de
  actualización se muestra con la fecha del último resumen disponible; un
  401/403 elimina el resumen. No se transforma un fallo en estadísticas cero.

## Flujo de uso

1. La abogada inicia sesión, completa el segundo factor si lo tiene habilitado
   y llega a Inicio (`/inicio`). Un acceso directo autorizado conserva su destino.
2. Consulta las cuatro tarjetas y su agenda de hoy. Pulsa un expediente, día o
   actividad para continuar en la pantalla correspondiente.
3. Abre los recordatorios en un modal, filtra un grupo y consulta páginas de diez.
4. Pulsa un recordatorio para abrir su detalle existente en Agenda y atenderlo.
5. Al volver a Inicio, obtiene un resumen nuevo. También puede actualizarlo con
   el botón; la pantalla refresca cada minuto mientras está visible y sin el
   modal de recordatorios abierto.

## Pruebas y límites de la verificación

`DashboardServiceTest` cubre zona horaria, cambio de año, final exclusivo,
actividades de varios días, límites de vistas previas, clasificación, exclusión
de estados, errores de consulta y paginación. `DashboardHttpTest` comprueba
autenticación, roles, autorización vigente, parámetros y cabeceras sin caché.

Se ejecutó sin levantar la API ni Docker:

```bash
mvn -o -Dtest='Dashboard*Test,Agenda*Test,Google*Test,CashHttpTest,Hu05HttpSecurityTest' test
```

Resultado: 69 pruebas ejecutadas correctamente y 13 pruebas de integración con
base de datos omitidas por su condición de ejecución. No se verificó esta
entrega contra una base de datos o una cuenta Google reales. Las pruebas del
navegador usan respuestas simuladas y se documentan en el frontend.
