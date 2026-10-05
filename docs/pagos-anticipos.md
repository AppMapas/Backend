# Pagos y anticipos por expediente (HU-08)

Subtareas entregadas: **modelo de datos** y **endpoints**. El frontend del resumen
financiero y del formulario de abono todavía no existe.

## Uso previsto

Como abogada, registrar en **Expedientes → detalle → Pagos** un anticipo o abono
(por ejemplo el 50% inicial) con su monto, tipo, forma de pago, concepto y fecha.
El resumen financiero del expediente muestra costo total, anticipos y saldo
pendiente desde cualquier dispositivo.

## Endpoints

Todos cuelgan de `/api/v1/legal-processes` y exigen rol `Abogada` o
`Administrador` con acceso de oficina vigente, igual que el resto del expediente.
Sin token → `401`; con otro rol → `403`, y en ambos casos el servicio ni se invoca.

### `GET /{caseId}/payments`

Devuelve el **resumen y la lista en una sola respuesta**:

```json
{
  "totalAmount": 10000.00, "paidAmount": 6500.50, "advancesAmount": 5000.00,
  "pendingAmount": 3499.50, "totalAgreed": true, "settled": false,
  "overpaid": false, "caseVersion": 3, "caseActive": true,
  "payments": [{
    "id": "6f1c…", "amount": 1500.50, "paymentType": "ABONO",
    "paymentMethod": "TRANSFERENCIA", "concept": "Abono",
    "paymentDate": "2026-10-04", "reference": null, "active": true,
    "registeredBy": "3002234560901", "registeredAt": "2026-10-04T17:49:52Z"
  }]
}
```

- `pendingAmount` va en `null` mientras no haya costo pactado; el frontend debe
  mostrar "sin costo pactado", nunca un pendiente de `Q 0.00`.
- `advancesAmount` solo cuenta los abonos de tipo `ANTICIPO`, para poder decir
  "50% inicial" sin leer la lista.
- `overpaid` es `true` cuando lo abonado supera el costo pactado.
- `caseVersion` es el que necesita el frontend para el `PUT` siguiente.
- La lista incluye los abonos anulados con `active: false`, tachados en pantalla.

`404` si el expediente no existe. Un expediente inactivo sí se puede **leer**.

### `POST /{caseId}/payments`

```json
{"requestId": "uuid", "amount": 5000.00, "paymentType": "ANTICIPO",
 "paymentMethod": "EFECTIVO", "concept": "50% inicial",
 "paymentDate": "2026-10-04", "reference": "recibo 1"}
```

Responde `201` con el resumen actualizado, o `200` con la cabecera
`Idempotency-Replayed: true` si esa clave ya tenía ese mismo abono (doble toque).
Devuelve el resumen y no solo el abono creado, para que la pantalla actualice
cifras y lista con una sola respuesta.

| Regla | Respuesta |
| --- | --- |
| Monto `0`, negativo, con 3 decimales o > 12 dígitos enteros | `400` nombrando el campo en `details.amount` |
| `concept` vacío o fecha futura | `400` en `details.concept` / `details.paymentDate` |
| Clave reusada con otro monto u otra abogada | `409` |
| Expediente inactivo | `409` |

### `PUT /{caseId}/total-amount`

`{"version": 3, "totalAmount": 10000.00}` fija el costo pactado. `totalAmount` en
`null` devuelve el expediente al estado "no pactado". `version` es el
`caseVersion` recibido en el `GET`; si alguien más editó el expediente, responde
`409` en lugar de pisar su cambio. También incrementa la versión.

### `DELETE /{caseId}/payments/{paymentId}`

**Anula**, no borra: la fila queda con `active = false` para que el historial
siga auditable, y el saldo pendiente vuelve a incluir ese dinero. Repetir la
anulación responde `409 "El abono ya está anulado."`. Un abono de otro
expediente responde `404`.

## Modelo

### `legal_process.total_amount`

Costo total pactado en quetzales para ese expediente. Vive en el expediente y no
en la plantilla de trámite: es una negociación por cliente y no debe cambiar si
alguien edita el catálogo.

`NULL` significa **"el costo todavía no está pactado"**, que es distinto de un
total de cero. El resumen financiero debe mostrar ambos casos por separado; un
`0` inventado daría un saldo engañoso. Por eso la columna es nullable y la
migración no rellena los expedientes anteriores: se completa al editarlos.

Rango admitted: `numeric(14,2)` con `CHECK (total_amount >= 0)`.

### `case_payment`

Un abono registrado. Flyway aplica `V9__create_case_payments.sql` al iniciar.

| Columna | Tipo | Notas |
| --- | --- | --- |
| `id` | `uuid` | Assignado por la aplicación, igual que `case_document`. |
| `legal_process_id` | `bigint` | Obligatorio, `ON DELETE RESTRICT`. |
| `amount` | `numeric(14,2)` | Exactamente 2 decimales, mayor que cero. |
| `payment_type` | `varchar(20)` | `ANTICIPO`, `ABONO`, `PAGO_FINAL`. |
| `payment_method` | `varchar(20)` | `EFECTIVO`, `TRANSFERENCIA`, `TARJETA`, `CHEQUE`, `OTRO`. |
| `concept` | `varchar(120)` | Obligatorio y no vacío: qué etapa del esquema paga. |
| `payment_date` | `date` | No puede ser futura; se registra lo ya recibido. |
| `reference` | `varchar(60)` | Opcional. Número de recibo o de transferencia. |
| `active` | `boolean` | `false` anula el abono sin borrarlo. |
| `registered_by` | `varchar(15)` | Abogada o administrador que lo registró. |
| `request_id`, `request_hash` | `uuid`, `varchar(64)` | Idempotencia: ambos nulos o ambos presentes. |
| `created_at`, `updated_at` | `timestamptz` | Mantenidos por la entidad. |

## Decisiones y por qué

- **`numeric(14,2)` y `BigDecimal`, nunca `double`.** El saldo se deriva sumando
  abonos; con punto flotante `0.10 + 0.20` da `0.30000000000000004` y el saldo
  no cuadra. Las pruebas lo verifican contra PostgreSQL real, y por HTTP un
  `1500.5` recibido queda guardado y devuelto como `1500.50`.
- **El saldo pendiente no se guarda.** Se deriva de los abonos vigentes en cada
  lectura. Una columna de saldo se desincroniza en cuanto se anula un abono.
- **El resumen sale de una sola consulta.** El total, los anticipos y el pendiente
  se calculan en Java recorriendo la lista que ya se devuelve, en vez de lanzar
  un `SUM` aparte. Así las cifras y las filas que las originan no pueden
  discrepar por una lectura intermedia.
- **Sobrepago no se bloquea, se muestra.** Si lo abonado supera el costo pactado,
  `pendingAmount` sale negativo y `overpaid` en `true`: saldo a favor del cliente.
  Bloquearlo impediría registrar un pago que de verdad se recibió, y el error de
  tipeo (15000 en vez de 1500) se detecta a la vista, en el resumen.
- **El cliente no se duplica en el abono.** Se hereda del expediente. La tabla
  heredada `payment_schedule` de `V1` sí guardaba `dpi_client`, lo que permitía
  asociar un pago a un cliente distinto del del trámite.
- **`payment_type` describe la etapa** (`ANTICIPO` / `ABONO` / `PAGO_FINAL`) y
  `concept` la describe en palabras ("50% inicial"). El catálogo está cerrado por
  `CHECK`; una prueba verifica que coincide con los enums de Java, para que
  agregar un valor al enum sin la migración no falle al guardar.
- **`active` en lugar de borrado físico.** Un registro financiero se anula, no se
  borra: así el historial sigue siendo auditable y el saldo baja sin perder rastro.
- **Idempotencia con `request_id`.** El registro se hace desde el teléfono; un
  doble toque creaba abonos duplicados. La respuesta perdida se resuelve
  reintentando con la misma clave.
- **Una sola moneda.** Todo está en quetzales (GTQ). No hay columna de moneda
  porque añadirla obligaría a cambiar tasas y montos ya capturados.

### Precisión: por qué hay un testigo en la entidad

`numeric(14,2)` **redondea en silencio**: un `CHECK` de escala sobre esa columna
siempre sería cierto, porque el valor ya viene redondeado de la columna. Por eso
`CasePaymentEntity` valida en `@PrePersist`/`@PreUpdate` que el monto sea mayor
que cero, tenga como máximo 2 decimales y no exceda los 12 dígitos enteros de la
columna. Sin eso, `10.005` se guardaría como `10.01`, distinto de lo que la
abogada quiso registrar.

## Hallazgo: tablas de pago heredadas de `V1`

`V1__create_schema.sql` creó `payment_schedule`, `income_record`, `expense_record`
y `payment_category`. **Ninguna tiene entidad, repositorio, servicio, controlador
ni uso en el frontend**: son esquema muerto del diseño original.

HU-08 **no las reutiliza** y **no las borra** (borrarlas sería una migración
destructiva fuera del alcance de esta subtarea). El motivo principal es que
`payment_schedule.amount` es `double precision`, incompatible con un control
financiero exacto, y su `payment_type` mezcla `POR_COBRAR` / `POR_PAGAR`, que es
otro concepto (cuentas por pagar del despacho, no abonos del cliente).

Recomendación para una HU posterior: decidir explícitamente si `expense_record` /
`income_record` se convierten en un módulo de gastos e ingresos, o si se eliminan.
Mientras tanto quedan inertes.

## Verificación

```sh
# Backend/legal-administrator
mvn test
```

Las pruebas unitarias (`CasePaymentEntityTest`, `CasePaymentServiceTest`,
`CasePaymentHttpTest`) cubren los invariantes del monto, la aritmética del saldo
hasta los centavos, la idempotencia, la anulación y el contrato HTTP (quién
puede llamar qué y qué se rechaza con `400` nombrando el campo).

Para comprobar la migración, las restricciones, la persistencia y el flujo
completo contra una base PostgreSQL **desechable** llamada `hu08_test`:

```sh
mvn test -Dhu08.integration=true \
  -Dhu08.test.url=jdbc:postgresql://127.0.0.1:55438/hu08_test \
  -Dhu08.test.user=USUARIO \
  -Dtest=Hu08MigrationTest,Hu08PersistenceTest,Hu08LedgerTest
```

`Hu08MigrationTest` comprueba que un expediente anterior a V9 sobrevive con
`total_amount` en `NULL` y que el esquema rechaza montos cero o negativos,
conceptos vacíos, tipos fuera de catálogo, fechas futuras, claves de idempotencia
inconsistentes y costos totales negativos. `Hu08PersistenceTest` comprueba el
ida y vuelta exacto de los montos (`ddl-auto=validate` valida el mapeo entidad ↔
esquema al cargar el contexto) y que un abono de otro expediente no sea
alcanzable. `Hu08LedgerTest` es la prueba del criterio de aceptación: registra
abonos por el servicio y verifica el pendiente derivado (incluido que no viva en
ninguna columna), el doble toque con la misma clave, la anulación, el bloqueo por
rol y el respaldo de la restricción única ante un doble cobro.

## Lo que falta (próximas subtareas)

- Frontend: resumen financiero (costo total, anticipos, pendientes) y formulario
  de abono, con la clave `requestId` generada en el cliente para que un doble
  toque no cobre dos veces.
- Decidir qué hacer con `expense_record` / `income_record` / `payment_category`
  de `V1`: convertirlas en un módulo de gastos e ingresos, o eliminarlas.