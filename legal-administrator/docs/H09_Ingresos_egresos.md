# H09 — Ingresos y egresos

## Alcance y decisiones

Caja centraliza los cobros recibidos por expedientes, los gastos de útiles de oficina y los gastos personales. Moneda única: GTQ. La dirección se deriva de la categoría y no puede elegirse libremente.

| Categoría | Dirección | Persistencia | Visibilidad |
| --- | --- | --- | --- |
| `TRAMITES` | Ingreso | `case_payment` | Abogada y Administrador de la oficina |
| `UTILES_OFICINA` | Egreso | `expense_record` | Abogada y Administrador de la oficina |
| `GASTOS_PERSONALES` | Egreso | `expense_record` | Exclusivamente el usuario que lo registró |

Los cobros se registran mediante `CasePaymentService`, también utilizado por Pagos y anticipos del expediente. No se crea una segunda fila en `income_record`. Registrar el mismo cobro desde ambos formularios con solicitudes independientes representaría dos cobros diferentes: la abogada debe utilizar un solo formulario por cobro real.

El costo pactado, las cuotas prometidas y los saldos por cobrar no son ingresos. Los cobros históricos de `case_payment` aparecen inmediatamente en Caja después de migrar.

## Datos y migración V10

- `payment_category`: códigos estables, estado activo y tres categorías iniciales. Las categorías anteriores quedan con código `LEGACY_<id>` e inactivas; sus filas se conservan.
- `expense_record`: dinero `numeric(14,2)`, categoría, descripción, fecha, método, referencia opcional, operador, propietario para gastos personales, UUID y huella de solicitud, fecha de registro, versión y auditoría de anulación.
- `case_payment`: añade motivo, fecha y operador de anulación. No modifica importes anteriores ni inventa auditoría de anulaciones históricas.
- `cash_movement`: vista de lectura que unifica ambos orígenes. No almacena otro saldo ni duplica movimientos.
- Restricciones y triggers impiden cambiar los datos económicos originales, reactivar registros anulados y eliminar físicamente movimientos. Un gasto personal exige propietario igual al operador y categoría compatible con su alcance.

**Antes de aplicar V10 a la base real**, realizar un respaldo y revisar mediante consultas de lectura:

```sql
SELECT count(*) AS ingresos_heredados FROM income_record;
SELECT count(*) AS egresos_heredados FROM expense_record;
```

V10 se detiene si cualquiera de las tablas heredadas contiene filas. Antes de habilitar Caja debe prepararse una conciliación que identifique cobros ya presentes en `case_payment`, propietario, categoría, fecha y medio de pago de cada gasto y precisión de los importes antiguos. No borrar las filas para saltarse este control. No convertir automáticamente importes `double precision` ni inventar propietarios. La transacción de migración conserva las tablas originales si falla esta comprobación.

La migración está implementada y probada en bases temporales; no se ejecutó contra la base de trabajo. Flyway la aplicará en el siguiente arranque autorizado del backend. Hibernate sigue usando `validate`, sin modificar el esquema por su cuenta.

## API

Las rutas de la tabla utilizan el prefijo `/api/v1`. Todas exigen JWT válido, autoridad `Abogada` o `Administrador` y confirmación de ese rol en la base de datos.

| Método y ruta | Función |
| --- | --- |
| `GET /cash/categories` | Catálogo de categorías y dirección |
| `GET /cash` | Resumen y página de movimientos, en una respuesta consistente |
| `POST /cash/incomes` | Cobro de un expediente activo, reutilizando el servicio de pagos |
| `GET /cash/incomes/{caseId}/summary` | Monto pactado, abonos, saldo y pagos finales vigentes del expediente |
| `POST /cash/expenses` | Gasto de oficina o personal |
| `GET /cash/expenses/{id}` | Consulta con control del propietario |
| `POST /cash/expenses/{id}/annul` | Anulación auditada del gasto |
| `POST /cash/incomes/{caseId}/{paymentId}/annul` | Anulación auditada del abono del expediente |

Filtros de `GET /cash`: `from`, `to`, `category`, `paymentMethod`, `status=ACTIVE|ALL|ANNULLED`, `q`, `caseId`, `page` y `size`. Por defecto: primer día del mes hasta hoy en Guatemala, vigentes, página 0 y 12 registros. Tamaño máximo: 100; búsqueda máxima: 100 caracteres. Busca descripción, referencia o código de expediente. `%`, `_` y `!` se interpretan literalmente; todos los valores usan parámetros SQL.

### Registrar gasto

```json
{
  "requestId": "86c49d86-eeca-435b-9d29-62a10dc20123",
  "category": "UTILES_OFICINA",
  "amount": "125.50",
  "description": "Compra de papel",
  "date": "2026-10-01",
  "paymentMethod": "EFECTIVO",
  "reference": null
}
```

Para ingreso: sustituir `category` por `caseId` y `paymentType=ANTICIPO|ABONO|PAGO_FINAL`. Métodos permitidos: `EFECTIVO`, `TRANSFERENCIA`, `TARJETA`, `CHEQUE`, `OTRO`.

Monto obligatorio y positivo, máximo `999999999999.99`, hasta dos decimales sin redondeo. Descripción obligatoria: 500 caracteres para gastos y 120 para cobros. Fecha obligatoria y no futura, según Guatemala. Referencia opcional hasta 60 caracteres. Operador, propietario, alcance y dirección los determina el servidor.

Creación: `201`; reintento confirmado: `200`, con `Idempotency-Replayed: true`. Cuerpo: `{source, sourceId, replayed, caseVersion}`. El mismo UUID y datos normalizados devuelve el mismo movimiento; cambiar contenido o usuario devuelve `409`. La clave tampoco puede reutilizarse entre gastos y los dos puntos de entrada de pagos.

### Anular

```json
{ "version": 0, "reason": "Registro equivocado" }
```

Usar la versión devuelta en el movimiento. Para ingresos es la versión del expediente; para gastos, la del gasto. Un conflicto devuelve `409` y exige recargar. Repetir la misma anulación, con el mismo operador y motivo, confirma el resultado sin modificarlo nuevamente. El reintento de un cobro o anulación ya guardados puede confirmarse aunque el expediente haya sido desactivado posteriormente; no habilita nuevas escrituras sobre expedientes inactivos.

El gasto devuelve el movimiento anulado; el ingreso devuelve una `Mutation`. No representa una devolución real de dinero. Para corregir un registro equivocado: anular, comprobar el resultado y registrar el movimiento correcto. Las devoluciones, saldos iniciales, conciliación bancaria, transferencias internas y reportes fiscales quedan fuera de H09.

Las rutas anteriores de pagos conservan sus contratos. La anulación `DELETE` anterior registra un motivo genérico de compatibilidad; las pantallas actualizadas utilizan la ruta de Caja con motivo escrito y versión.

## Información del expediente antes de cobrar

Al seleccionar un expediente en el formulario de Caja, `GET /cash/incomes/{caseId}/summary` consulta el libro existente de pagos mediante `CasePaymentService`. Devuelve `totalAmount`, `paidAmount`, `pendingAmount`, `totalAgreed`, `settled`, `overpaid`, `caseActive`, `caseVersion`, `finalPaymentAmount`, `finalPaymentCount` y `lastFinalPaymentDate`, además de `caseId`.

Todos los importes son cadenas decimales exactas. Sin costo pactado, total y saldo son `null`; no se inventa un precio. Solo los pagos `PAGO_FINAL` vigentes cuentan en el importe, cantidad y fecha del último pago final. Un pago final anulado no se muestra como recibido. La consulta conserva permisos de oficina, responde sin caché y utiliza una instantánea consistente de lectura.

El monto total pactado se muestra como referencia al registrar el ingreso. La interfaz ofrece **Usar saldo como pago final** cuando hay saldo positivo y el expediente está activo; esta acción completa monto y tipo del formulario, pero no registra ni suma ingresos hasta confirmar el dinero recibido. Si el costo está cubierto o hay saldo a favor, no se propone otro pago final. Se mantienen las reglas existentes para abonos y sobrepagos.

La información aparece junto al expediente seleccionado y durante la confirmación. Una consulta fallida bloquea un cobro nuevo; los reintentos de registros pendientes conservan su solicitud original. Si al actualizar la consulta cambian los importes revisados, la interfaz vuelve al formulario y pide revisarlos, incluso si antes falló la consulta. Este control visual no reserva el saldo: las escrituras mantienen sus controles transaccionales existentes.

## Balance

Importes y agregados de Caja viajan como **cadenas decimales**. PostgreSQL usa `numeric` y Java usa `BigDecimal`; el frontend no necesita sumar números de coma flotante.

```text
balanceOficina = ingresosVigentes - gastosOficinaVigentes
balanceGeneral = balanceOficina - gastosPersonalesVigentesDelUsuario
```

El resumen aplica exactamente los mismos filtros y permisos que el listado, pero sobre todas las filas filtradas, no solo la página. Los anulados contribuyen cero. La consulta usa `REPEATABLE_READ` para que resumen, cantidad y página correspondan a la misma instantánea. El balance incluye todos los medios de pago, por lo que no equivale al efectivo físico disponible.

## Controles de seguridad

- Autorización en filtro HTTP, controlador y servicio; un rol revocado en la base bloquea un JWT todavía vigente.
- Gastos personales excluidos tanto de filas como de agregados de otros usuarios; acceso por identificador ajeno devuelve `404` sin revelar su existencia.
- UUID, huella SHA-256 del contenido y bloqueo transaccional por solicitud; restricciones únicas y transacciones evitan doble inserción.
- Bloqueo del expediente para escrituras financieras, bloqueo del gasto al anular y comprobación de versión. Tiempo máximo de las operaciones de Caja: 15 segundos; un fallo revierte la transacción.
- Campos económicos inmutables, ausencia de borrado financiero y auditoría de anulaciones.
- Respuestas financieras sin caché, CORS con orígenes explícitos y consultas parametrizadas. Los errores no exponen SQL ni trazas al cliente.
- Perfil `prod`: suprime SQL, parámetros JDBC y detalles del error estándar. Activarlo con `SPRING_PROFILES_ACTIVE=prod` al desplegar.

Para operar con datos reales, el despliegue debe proporcionar HTTPS, credenciales propias, secreto JWT robusto, orígenes CORS exactos y respaldos restaurables. Sustituir las cuentas y contraseñas de demostración de V2. Separar permisos de migración y aplicación en PostgreSQL; el usuario de ejecución no debe poder alterar tablas, desactivar triggers ni cambiar roles de la base. Estas condiciones pertenecen al entorno de despliegue y no se verificaron contra producción.

## Flujo de la licenciada

1. Inicia sesión y abre **Caja**.
2. Consulta el período, cobros, gastos y balances; ajusta fechas, categoría, estado o búsqueda.
3. Selecciona **Registrar movimiento** y la categoría.
4. Para un cobro, busca el expediente activo, consulta monto pactado, abonos, saldo y pagos finales vigentes, y elige anticipo, abono o pago final. Puede completar el saldo con **Usar saldo como pago final** y revisar lo recibido. Para un gasto personal, la titularidad es automática.
5. Completa monto, descripción, fecha, método y referencia opcional; revisa y confirma.
6. Ve la tarjeta flotante de confirmación y los datos actualizados. El cobro también aparece en Pagos y anticipos del expediente.
7. Si falta confirmación, resuelve el intento pendiente con el mismo UUID antes de registrar otro movimiento.
8. Si registró datos incorrectos, anula con motivo y vuelve a registrar la corrección.

## Pruebas

La verificación completa ejecutó **201 pruebas backend**, incluidas H09 y regresiones de clientes, expedientes, documentos, pagos, autenticación y cálculos: cero fallos. Las pruebas PostgreSQL utilizaron bases desechables con nombres validados; nunca `.env`, Docker ni un servidor HTTP.

La corrección del resumen de pago final añadió pruebas de importes exactos, costo sin pactar, saldo a favor, exclusión de anulaciones y permisos HTTP. La suite focalizada `Cash*Test,CasePayment*Test` ejecutó **62 pruebas**, sin fallos y sin base de datos ni servidor HTTP.

```bash
mvn test
```

Pruebas H09 con una base PostgreSQL local **exclusivamente de pruebas**:

```bash
mvn -Dtest='H09*Test' -Dh09.integration=true \
  -Dh09.test.url=jdbc:postgresql://127.0.0.1:55439/h09_test \
  -Dh09.test.user=krm test
```

La base `h09_test` debe ser desechable y estar disponible antes de ese comando; las pruebas no la crean ni arrancan PostgreSQL. Las integraciones no se habilitan en `mvn test` sin sus propiedades explícitas. Si se cambia de rama, usar `mvn clean test` para evitar recursos y clases antiguos en `target`.
