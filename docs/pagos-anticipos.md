# Pagos y anticipos por expediente (HU-08)

Subtarea entregada: **modelo de datos**. No hay endpoints ni frontend todavía;
el contrato HTTP se definirá en la subtarea del endpoint de registro.

## Uso previsto

Como abogada, registrar en **Expedientes → detalle → Pagos** un anticipo o abono
(por ejemplo el 50% inicial) con su monto, tipo, forma de pago, concepto y fecha.
El resumen financiero del expediente muestra costo total, anticipos y saldo
pendiente desde cualquier dispositivo.

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
  no cuadra. Las pruebas lo verifican contra PostgreSQL real.
- **El saldo pendiente no se guarda.** Se deriva como
  `total_amount - CasePaymentRepository.sumActiveAmount(caseId)`. Una columna de
  saldo se desincroniza en cuanto se anula un abono.
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

Las pruebas unitarias (`CasePaymentEntityTest`) cubren los invariantes del monto,
la aritmética exacta frente a `double` y la coherencia enum ↔ restricciones.

Para comprobar la migración, las restricciones y la persistencia reales contra
una base PostgreSQL **desechable** llamada `hu08_test`:

```sh
mvn test -Dhu08.integration=true \
  -Dhu08.test.url=jdbc:postgresql://127.0.0.1:55438/hu08_test \
  -Dhu08.test.user=USUARIO \
  -Dtest=Hu08MigrationTest,Hu08PersistenceTest
```

`Hu08MigrationTest` comprueba que un expediente anterior a V9 sobrevive con
`total_amount` en `NULL` y que el esquema rechaza montos cero o negativos,
conceptos vacíos, tipos fuera de catálogo, fechas futuras, claves de idempotencia
inconsistentes y costos totales negativos. `Hu08PersistenceTest` comprueba el
ida y vuelta exacto de los montos (`ddl-auto=validate` valida el mapeo entidad ↔
esquema al cargar el contexto), la suma de abonos vigentes, el orden del listado
y que un abono de otro expediente no sea alcanzable.

## Lo que falta (próximas subtareas)

- Endpoint para registrar, listar y anular abonos, y para leer/escribir
  `total_amount`, con validación de `@Digits(fraction = 2)` en el DTO.
- Rechazo de abonos que superen el saldo pactado: es una regla de servicio,
  porque necesita el agregado de los abonos vigentes.
- Exponer el saldo pendiente. Se calcula en el servicio, no se almacena.
- Frontend del resumen financiero y del formulario de abono.