# Documentos de expedientes

## Uso

En **Expedientes → detalle → Documentos adjuntos**, seleccionar archivos, galería
o cámara y pulsar **Guardar archivos pendientes**. Cada archivo se envía por
separado; los errores se muestran por archivo y los guardados no se reenvían.
Consultar muestra imágenes/PDF; Descargar recupera el original.

- PDF, JPG/JPEG y PNG, hasta **20 MiB por archivo** por defecto.
- La API valida extensión, MIME declarado, firma del contenido, tamaño y nombre.
- La cámara depende del soporte del navegador y dispositivo. HEIC no está permitido.
- Archivos vacíos, formatos no admitidos y expedientes inactivos se rechazan.
- PostgreSQL conserva metadatos, expediente, usuario, fecha, proveedor y clave UUID.
- Flyway aplica `V8__create_case_documents.sql` automáticamente al iniciar.
- El acceso sigue la política del despacho: **Abogada y Administrador**, verificando
  también el rol vigente. Al recuperar un documento se verifica su expediente.
- Archivos y rutas de almacenamiento nunca se publican como recursos estáticos.
  Consulta y descarga requieren Bearer y devuelven `Cache-Control: no-store`.

## Límite de subida

| Variable | Predeterminado | Significado |
| --- | --- | --- |
| `DOCUMENT_MAX_FILE_SIZE` | `20MB` | Tope por archivo; la API lo publica en `/documents/policy` |
| `DOCUMENT_MAX_REQUEST_SIZE` | `21MB` | Tope de la petición multipart; incluye el envoltorio |

Se eligió **20 MiB** porque en la práctica un expediente se documenta con
escrituras, contratos y escaneos de varias páginas, no con archivos de diseño de
gran tamaño; 20 MiB cubre esos casos con holgura y mantiene acotada la memoria y
el ancho de banda del despliegue. El límite es configurable hasta 50 MiB.

La subida **no carga el archivo en memoria**:

- `spring.servlet.multipart.file-size-threshold=0B` obliga al contenedor a escribir
  la petición en el directorio temporal desde el primer byte; el archivo temporal
  lo elimina el contenedor al finalizar la petición.
- `DocumentValidator` recorre el flujo en bloques de 8 KiB contando bytes reales y
  conservando solo los 8 primeros y los últimos 1024 para verificar la firma.
- `DocumentStorage` recibe un `InputStream`: el almacenamiento local copia por
  bloques y GCS sube con `createFrom` en trozos de 256 KiB.

Coste asumido: validación y escritura leen el flujo dos veces, contra el disco
temporal o la red, sin retenciones del archivo completo.

La **descarga** sí arma el archivo en memoria para responderlo (`byte[]` con
`Content-Length`), por lo que queda acotada por el mismo límite de 20 MiB. Con
pocas consultas simultáneas es aceptable; si la concurrencia creciera, migrar el
`content` a `StreamingResponseBody` sin cambiar el contrato HTTP.

`DOCUMENT_MAX_REQUEST_SIZE` debe superar siempre a `DOCUMENT_MAX_FILE_SIZE`; si no,
la aplicación no arranca. Al cambiar el límite, ajustar también el del proxy o de
la plataforma (Cloud Run) delante del backend.

## Entorno local (predeterminado)

No requiere cuenta ni credenciales de Google Cloud.

```dotenv
DOCUMENT_STORAGE_PROVIDER=local
DOCUMENT_LOCAL_DIRECTORY=./data/documents
DOCUMENT_MAX_FILE_SIZE=20MB
DOCUMENT_MAX_REQUEST_SIZE=21MB
```

La ruta es relativa al directorio de ejecución del backend. Fuera de Docker, exportar
estas variables o incluirlas en `.env` en ese directorio (Spring lo importa).
El directorio se crea con permisos `700` y cada documento con `600` cuando el
sistema de archivos es POSIX. Si el directorio está montado con otro propietario y
el chmod falla, el servicio registra un aviso y continúa en lugar de impedir la
carga. El directorio no se sirve como recurso estático.

Con `Backend/docker-compose.yml`, los archivos se guardan en el volumen persistente
`legal_document_data`, montado en `/app/data/documents`; Compose transmite las
variables desde `Backend/.env`. Recrear el contenedor conserva los archivos.
`docker compose down -v` elimina los volúmenes, incluyendo documentos y base de datos.
Respaldar tanto PostgreSQL como el directorio/volumen de documentos.

## Despliegue en Google Cloud

Crear un bucket privado con acceso uniforme y prevención de acceso público:

```sh
gcloud storage buckets create gs://NOMBRE_BUCKET --location=REGION --uniform-bucket-level-access --public-access-prevention
gcloud storage buckets add-iam-policy-binding gs://NOMBRE_BUCKET --member=serviceAccount:CUENTA_SERVICIO --role=roles/storage.objectUser
```

Asignar esa cuenta de servicio al backend (por ejemplo, Cloud Run) y configurar:

```dotenv
DOCUMENT_STORAGE_PROVIDER=gcs
DOCUMENT_GCS_BUCKET=NOMBRE_BUCKET
DOCUMENT_MAX_FILE_SIZE=20MB
DOCUMENT_MAX_REQUEST_SIZE=21MB
```

El cliente usa **Application Default Credentials** de la cuenta del despliegue;
no se necesitan claves JSON en el repositorio. Para probar GCS desde una máquina
de desarrollo, usar `gcloud auth application-default login` con una identidad
que tenga acceso al bucket. El navegador no necesita credenciales de Google ni
CORS del bucket: los bytes pasan por la API autenticada. Google Cloud Storage
cifra los objetos en reposo; desplegar la API detrás de HTTPS.

El modo local no es almacenamiento persistente adecuado para el filesystem
efímero de Cloud Run: configurar `gcs` allí. Un proveedor desconocido o `gcs`
sin bucket impide arrancar con una configuración equivocada.

Cada documento conserva el proveedor con el que se guardó. Cambiar el proveedor
solo afecta nuevas cargas; **no migra documentos locales existentes**. Para conservar
datos de desarrollo al desplegar, copiar sus objetos (manteniendo las claves UUID)
al bucket y actualizar `storage_provider` a `gcs` después de verificar la copia,
junto con la migración de la base de datos. No cambiar el bucket/directorio de datos
existentes sin trasladar también sus objetos.

## Metadatos y dirección del archivo

`POST /{caseId}/documents` (multipart, campo `file`) guarda el archivo en el
almacenamiento y persiste **solo metadatos** en `case_document`:

| Columna | Contenido |
| --- | --- |
| `id` | UUID del documento; también es la clave del objeto |
| `legal_process_id` | Expediente al que se asocia (FK a `legal_process`) |
| `original_name` | Nombre que envió el cliente, para mostrar y descargar |
| `content_type`, `size_bytes` | Tipo y tamaño verificados |
| `storage_provider` | `local` o `gcs`, según dónde se guardó |
| `object_key` | **Dirección del archivo** dentro del almacenamiento (UUID) |
| `uploaded_by`, `uploaded_at` | Quién lo subió y cuándo |

El contenido **nunca** se guarda en PostgreSQL: la tabla no tiene columnas
binarias y `object_key` es una clave interna, nunca el nombre del cliente. La
respuesta `201` devuelve únicamente `id`, `name`, `contentType`, `sizeBytes` y
`uploadedAt`; la dirección interna y el proveedor no se exponen. Para leer el
archivo se usa `GET /{caseId}/documents/{id}/content`, que verifica que el
documento pertenezca al expediente indicado.

El objeto se escribe antes del commit de los metadatos; si la transacción
revierte, se elimina el objeto.

## Contrato HTTP

Todas las rutas requieren `Authorization: Bearer <accessToken>`.

| Método | Ruta bajo `/api/v1/legal-processes` | Respuesta |
| --- | --- | --- |
| GET | `/documents/policy` | `maxFileSize` en bytes y `extensions` |
| GET | `/{caseId}/documents` | Lista de metadatos, recientes primero |
| POST | `/{caseId}/documents` | Multipart con campo `file`; 201 + metadatos |
| GET | `/{caseId}/documents/{id}/content` | Bytes con disposición inline |
| GET | `/{caseId}/documents/{id}/content?download=true` | Bytes con disposición attachment |

Metadatos: `id`, `name`, `contentType`, `sizeBytes`, `uploadedAt`.
Errores JSON: 400 archivo inválido, 401 sesión requerida, 403 sin permiso,
404 expediente/documento inexistente, 409 expediente inactivo,
413 tamaño excedido y 503 almacenamiento no disponible.

Configurar el máximo de solicitud por encima del máximo de archivo para permitir
el envoltorio multipart. `MB` de Spring equivale aquí a MiB (1024 × 1024 bytes).
El límite de archivo configurable admite hasta 50 MiB; ajustar además el límite
del proxy/plataforma si se modifica. El frontend obtiene el límite desde la API.

La carga de objeto precede al commit de metadatos. Si la transacción revierte,
se elimina el objeto; si esa limpieza falla, se registra su UUID en el log.
Una interrupción abrupta del proceso puede dejar un objeto sin metadatos; estos
objetos no son consultables desde la API. La carga no es idempotente: ante una
respuesta perdida, actualizar el listado antes de reintentar para evitar duplicados.

## Verificación

```sh
# Backend/legal-administrator
mvn test
# Frontend/legal_administrator_frontend, Node ^22.18.0 o >=24.12.0
npm test
npm run build
```

Las pruebas de documentos cubren validación, streaming de firma y tamaño,
almacenamiento local con permisos privados y limpieza ante fallo, rollback,
fallos del proveedor, pertenencia al expediente, permisos HTTP y carga multipart.
Para comprobar la migración, persistencia y rollback reales contra una base
PostgreSQL **desechable** llamada `hu05_test`:

```sh
mvn test -Dhu05.integration=true -Dhu05.test.url=jdbc:postgresql://127.0.0.1:55438/hu05_test -Dhu05.test.user=USUARIO -Dtest=DocumentPersistenceTest,Hu05MigrationTest
```

Para pruebas manuales: cargar PDF/foto, recargar la página y reiniciar el backend,
consultar/descargar, intentar cargar un formato no permitido o un archivo mayor
al límite, y probar cámara/galería en un teléfono real.
