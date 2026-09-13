# 📐 Cálculos de Área — `/api/v1/calculations`

Módulo encargado de la conversión de unidades de medida (varas, metros, etc.) y el cálculo y almacenamiento del área total de terrenos mediante colindancias.

> [!IMPORTANT]
> Todos los endpoints de este módulo requieren **autenticación con Bearer JWT**.

---

## POST `/api/v1/calculations/convert`

Convierte una lista de medidas desde distintas unidades al sistema métrico (metros).

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Request Body

```json
[
  {
    "value": 10.5,
    "unit": "vara"
  },
  {
    "value": 25.0,
    "unit": "metro"
  }
]
```

> El cuerpo es un **array** de objetos de medición.

| Campo   | Tipo   | Obligatorio | Validaciones                        |
|---------|--------|-------------|-------------------------------------|
| `value` | Double | ✅ Sí        | Debe ser mayor a cero (`@Positive`) |
| `unit`  | String | ✅ Sí        | Unidad de medida a convertir        |

**Unidades soportadas:** `metro`, `vara`, `cuadra`, `manzana`, `cuerda`, `hectarea`, `pie`, `pulgada`

### Response `200 OK`

```json
[
  {
    "originalValue": 10.5,
    "unit": "vara",
    "convertedValueMeters": 8.799
  },
  {
    "originalValue": 25.0,
    "unit": "metro",
    "convertedValueMeters": 25.0
  }
]
```

| Campo                  | Tipo   | Descripción                               |
|------------------------|--------|-------------------------------------------|
| `originalValue`        | Double | Valor original enviado                    |
| `unit`                 | String | Unidad de medida original                 |
| `convertedValueMeters` | Double | Valor convertido a metros lineales        |

---

## POST `/api/v1/calculations/save`

Guarda un cálculo de área completo (terreno, colindancias y medidas) asociado a un cliente y usuario del sistema.

**Autenticación requerida:** ✅ Bearer JWT  
**Roles permitidos:** Cualquier usuario autenticado

### Query Parameters

| Parámetro      | Tipo   | Obligatorio | Descripción                                  |
|----------------|--------|-------------|----------------------------------------------|
| `clientDpi`    | String | ✅ Sí        | DPI del cliente propietario del terreno      |
| `userSystemId` | String | ✅ Sí        | DPI del usuario del sistema que registra     |

### Request Body

```json
{
  "clientDpi": "2541234560101",
  "userSystemId": "3001123450101",
  "terrainName": "Terreno Los Pinos",
  "generalDescription": "Lote de terreno ubicado en zona urbana",
  "propertyType": "URBANA",
  "boundaries": [
    {
      "sideNumber": 1,
      "referencePoint": "Colinda con calle principal",
      "orientation": "N",
      "measurements": [
        {
          "value": 25.5,
          "unit": "varas"
        }
      ]
    },
    {
      "sideNumber": 2,
      "referencePoint": "Colinda con propiedad privada",
      "orientation": "S",
      "measurements": [
        {
          "value": 25.5,
          "unit": "varas"
        }
      ]
    },
    {
      "sideNumber": 3,
      "referencePoint": "Colinda con callejón",
      "orientation": "E",
      "measurements": [
        {
          "value": 40.0,
          "unit": "varas"
        }
      ]
    }
  ]
}
```

#### `AreaCalculationRequestDto`

| Campo                | Tipo                  | Obligatorio | Descripción                                       |
|----------------------|-----------------------|-------------|---------------------------------------------------|
| `clientDpi`          | String                | ✅ Sí        | DPI del cliente dueño del terreno                 |
| `userSystemId`       | Long                  | ✅ Sí        | ID del usuario del sistema que registra           |
| `terrainName`        | String                | ✅ Sí        | Nombre del terreno o finca                        |
| `generalDescription` | String                | ❌ No        | Descripción general del terreno                   |
| `propertyType`       | String                | ✅ Sí        | Tipo de propiedad (`RURAL`, `URBANA`, etc.)       |
| `boundaries`         | Array\<BoundaryRequestDto\> | ✅ Sí  | Lista de colindancias (mínimo 3)                  |

#### `BoundaryRequestDto`

| Campo            | Tipo                       | Obligatorio | Descripción                                      |
|------------------|----------------------------|-------------|--------------------------------------------------|
| `sideNumber`     | Long                       | ✅ Sí        | Número de lado de la colindancia                 |
| `referencePoint` | String                     | ❌ No        | Punto de referencia geográfico                   |
| `orientation`    | String                     | ❌ No        | Orientación cardinal (`N`, `S`, `E`, `O`)        |
| `measurements`   | Array\<MeasurementRequestDto\> | ✅ Sí   | Lista de medidas para este lado                  |

#### `MeasurementRequestDto`

| Campo   | Tipo   | Obligatorio | Validaciones                        |
|---------|--------|-------------|-------------------------------------|
| `value` | Double | ✅ Sí        | Debe ser mayor a cero (`@Positive`) |
| `unit`  | String | ✅ Sí        | Unidad de medida                    |

### Response `200 OK`

```json
{
  "id": 1,
  "clientUser": {
    "dpi": "9876543210123"
  },
  "userSystem": {
    "dpi": "1234567890123"
  },
  "terrainName": "Finca El Roble",
  "generalDescription": "Terreno ubicado en zona rural de San Marcos",
  "totalAreaSquareMeters": 1350.75,
  "legalNotice": "El área total del terreno es de 1350.75 metros cuadrados...",
  "createdAt": "2026-09-12",
  "updatedAt": null,
  "propertyType": "RURAL"
}
```

| Campo                  | Tipo    | Descripción                                            |
|------------------------|---------|--------------------------------------------------------|
| `id`                   | Long    | ID único del cálculo generado                          |
| `clientUser`           | Object  | Referencia al cliente propietario                      |
| `userSystem`           | Object  | Referencia al usuario del sistema que registró         |
| `terrainName`          | String  | Nombre del terreno                                     |
| `generalDescription`   | String  | Descripción general del terreno                        |
| `totalAreaSquareMeters`| Double  | Área total calculada en metros cuadrados               |
| `legalNotice`          | String  | Aviso legal generado automáticamente con el área       |
| `createdAt`            | Date    | Fecha de creación del registro (`YYYY-MM-DD`)          |
| `updatedAt`            | Date    | Fecha de última actualización (puede ser `null`)       |
| `propertyType`         | String  | Tipo de propiedad                                      |
