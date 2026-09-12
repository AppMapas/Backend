-- ==========================================
-- SCRIPT DE INSERCIÓN DE DATOS INICIALES
-- ==========================================

-- 1. Inserción de Roles solicitados
INSERT INTO role (name, description)
VALUES ('Administrador', 'Control total de la plataforma y configuraciones del sistema'),
       ('Abogada', 'Encargada de la gestión jurídica, revisión legal y cálculos de áreas'),
       ('Secretaria',
        'Encargada de la atención al cliente, registro y agendamiento de citas') ON CONFLICT (name) DO NOTHING;

-- 2. Inserción de País base (Nacionalidad)
INSERT INTO country (name, iso_code)
VALUES ('Guatemala', 'GTM') ON CONFLICT (iso_code) DO NOTHING;

-- 3. Inserción de Estados Civiles básicos
INSERT INTO marital_status (name, description)
VALUES ('Soltero/a', 'Persona que no se ha casado ni tiene una unión de hecho legal.'),
       ('Casado/a', 'Persona que contrajo matrimonio civil o religioso con efectos legales.'),
       ('Unión de hecho',
        'Situación reconocida judicial o notarialmente cuadno un hombre y una mujer viven juntos por más de tres años con vísperas de estabilidad.'),
       ('Divorciado/a', 'Persona cuyo vínculo matrimonial fue disuelto legalmente.'),
       ('Viudo/a', 'Persona cuyo cónyugue ha fallecido.') ON CONFLICT (name) DO NOTHING;

-- 4. Inserción de Departamentos de Guatemala
INSERT INTO department(numerical_code, name)
VALUES ('01', 'Guatemala'),
       ('02', 'El Progreso'),
       ('03', 'Sacatepéquez'),
       ('04', 'Chimaltenango'),
       ('05', 'Escuintla'),
       ('06', 'Santa Rosa'),
       ('07', 'Sololá'),
       ('08', 'Totonicapán'),
       ('09', 'Quetzaltenango'),
       ('10', 'Suchitepéquez'),
       ('11', 'Retalhuleu'),
       ('12', 'San Marcos'),
       ('13', 'Huehuetenango'),
       ('14', 'Quiché'),
       ('15', 'Baja Verapaz'),
       ('16', 'Alta Verapaz'),
       ('17', 'Petén'),
       ('18', 'Izabal'),
       ('19', 'Zacapa'),
       ('20', 'Chiquimula'),
       ('21', 'Jalapa'),
       ('22', 'Jutiapa') ON CONFLICT (numerical_code) DO NOTHING;

-- 5. Inserción de Municipios de ejemplo para los departamentos
INSERT INTO municipality (name, numerical_code_department)
VALUES ('Quetzaltenango', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Salcajá', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Olintepeque', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('San Carlos Sija', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Sibilia', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Cabricán', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Cajolá', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('San Miguel Sigüilá', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('San Juan Ostuncalco', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('San Mateo', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Concepción Chiquirichapa', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('San Martín Sacatepéquez', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Almolonga', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Cantel', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Huitán', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Zunil', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Colomba Costa Cuca', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('San Francisco La Unión', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('El Palmar', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Coatepeque', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Génova', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Flores Costa Cuca', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('La Esperanza', (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')),
       ('Palestina de Los Altos',
        (SELECT numerical_code FROM department WHERE name = 'Quetzaltenango')) ON CONFLICT (name, numerical_code_department) DO NOTHING;


-- 6 Inserción de Usuarios iniciales
INSERT INTO user_system (dpi, first_name, last_name, age, email, password_hash, id_marital_status, id_nationality,
                         id_role, created_at)
VALUES ('3001123450101', 'Carlos', 'Administrador', 35, 'admin@system.com',
        '$2a$10$yxNfSky3hf8I0uSH85Kn3.RpAYo7K4YD9GwYIeeV37hKePfMNReNe', 1, 1, 1, CURRENT_DATE),
       ('3002234560901', 'Ana', 'Gómez Pérez', 30, 'abogada@system.com',
        '$2a$10$gmbcYuze8w0kiTY5V79rhenHJcZLoS0rw6UPpRhSuOx16UqwOB4Ma', 1, 1, 2,
        CURRENT_DATE),
       ('3003345670301', 'Rocío', 'López Ruiz', 26, 'secretaria@system.com',
        '$2a$10$wr7.kTAL1n68Fxt7dLx.U.vAco/RxrB8hdqcKCAtmYY8Y7WDpUSWq', 1, 1, 3,
        CURRENT_DATE) ON CONFLICT (dpi) DO NOTHING;