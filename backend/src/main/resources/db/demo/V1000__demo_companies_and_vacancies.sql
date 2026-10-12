-- DATOS DE EJEMPLO (spec 004, seccion 5). Todo es FICTICIO: empresas, vacantes y textos inventados, sin marcas
-- reales, sin logos y sin nombres de personas. Flyway solo ve esta carpeta (classpath:db/demo) cuando
-- talenti.demo-data.enabled=true (variable TALENTI_DEMO_DATA); en produccion queda apagada.
-- Las versiones V1000 en adelante no chocan con las migraciones de esquema (db/migration).
-- Los identificadores son fijos para que las pruebas y las capturas sean estables.
-- Las fechas de publicacion son relativas al momento de la carga (de hoy a 14 dias atras).

-- ---------------------------------------------------------------------------
-- Empresas (8; 4 destacadas)
-- ---------------------------------------------------------------------------
INSERT INTO company (id, name, city, short_description, featured) VALUES
    ('c0000000-0000-4000-8000-000000000001', 'Nodo Digital', 'Lima',
     'Desarrolla sitios web y tiendas en línea para negocios que empiezan a vender por internet.', true),
    ('c0000000-0000-4000-8000-000000000002', 'Andes Tech', 'Lima',
     'Construye servicios en la nube y sistemas internos para pequeñas y medianas empresas.', true),
    ('c0000000-0000-4000-8000-000000000003', 'Cóndor Datos', 'Lima',
     'Ordena y analiza datos para que otras empresas tomen mejores decisiones.', true),
    ('c0000000-0000-4000-8000-000000000004', 'Urpi Cloud', 'Lima',
     'Ofrece infraestructura y automatización en la nube para equipos de software.', true),
    ('c0000000-0000-4000-8000-000000000005', 'Punto Código', 'Arequipa',
     'Revisa y prueba software para encontrar errores antes de que lo usen los clientes.', false),
    ('c0000000-0000-4000-8000-000000000006', 'Ruta Labs', 'Lima',
     'Crea aplicaciones móviles para transporte y reparto de pedidos.', false),
    ('c0000000-0000-4000-8000-000000000007', 'Quipu Systems', 'Lima',
     'Desarrolla sistemas de gestión para tiendas, almacenes y negocios familiares.', false),
    ('c0000000-0000-4000-8000-000000000008', 'Misti Software', 'Arequipa',
     'Fábrica de software que crea sistemas administrativos a la medida.', false);

-- ---------------------------------------------------------------------------
-- Vacantes (12 abiertas; 4 destacadas): 4 remotas, 5 híbridas, 3 presenciales; Lima 5, Arequipa 3.
-- ---------------------------------------------------------------------------
INSERT INTO vacancy (id, company_id, title, summary, description, modality, location, hours_per_week,
                     published_at, status, featured) VALUES
    ('d0000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000002',
     'Practicante Backend Java',
     'Apoya al equipo en la programación de servicios web con Java y Spring Boot, y en las consultas a la base de datos.',
     'Te sumarás al equipo que mantiene los servicios de un sistema de reservas. Aprenderás a crear y probar servicios web con Java y Spring Boot, a escribir consultas SQL y a revisar el código con tus compañeros. Habrá una reunión corta cada día y una revisión de avances cada semana. No necesitas experiencia laboral: buscamos ganas de aprender y buenas bases de programación.',
     'REMOTE', NULL, 20, now() - interval '2 days 3 hours', 'OPEN', true),
    ('d0000000-0000-4000-8000-000000000002', 'c0000000-0000-4000-8000-000000000001',
     'Practicante Frontend',
     'Construye pantallas web con JavaScript, HTML y CSS en proyectos reales de comercio electrónico.',
     'Trabajarás con el equipo de diseño y de programación para armar las pantallas de tiendas en línea: catálogo, carrito y pago. Aprenderás a escribir HTML y CSS ordenados, a usar JavaScript y a cuidar que las páginas se vean bien en celulares. Tendrás una persona que te guía en cada tarea.',
     'HYBRID', 'Lima', 24, now() - interval '3 days 5 hours', 'OPEN', true),
    ('d0000000-0000-4000-8000-000000000003', 'c0000000-0000-4000-8000-000000000001',
     'Practicante React y TypeScript',
     'Desarrolla componentes con React y TypeScript para una plataforma de pedidos en línea.',
     'Participarás en la creación de componentes reutilizables con React y TypeScript. Escribirás pruebas sencillas, consumirás servicios web y aprenderás a trabajar con ramas y revisiones de código en Git. El trabajo es remoto, con una reunión diaria de coordinación.',
     'REMOTE', NULL, 20, now() - interval '1 day 2 hours', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000004', 'c0000000-0000-4000-8000-000000000005',
     'Practicante de Calidad',
     'Prueba aplicaciones web, documenta los errores que encuentres y aprende a automatizar pruebas.',
     'Ejecutarás pruebas manuales sobre aplicaciones web, registrarás los errores con pasos claros y aprenderás a probar servicios con Postman. Con el tiempo podrás empezar a automatizar algunas pruebas. El trabajo es híbrido: dos días en la oficina de Arequipa y el resto desde casa.',
     'HYBRID', 'Arequipa', 20, now() - interval '4 days 1 hour', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000005', 'c0000000-0000-4000-8000-000000000006',
     'Practicante iOS',
     'Colabora en la programación de una aplicación móvil para iPhone con Swift.',
     'Formarás parte del equipo que desarrolla una aplicación de reparto para iPhone. Aprenderás Swift aplicado a un producto real, a conectar la aplicación con servicios web y a probar tu trabajo en dispositivos. Se trabaja en nuestra oficina de Lima, junto al resto del equipo.',
     'ONSITE', 'Lima', 30, now() - interval '6 days 4 hours', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000006', 'c0000000-0000-4000-8000-000000000006',
     'Practicante Android',
     'Apoya el desarrollo de una aplicación móvil para Android con Kotlin.',
     'Ayudarás a crear pantallas y funciones de una aplicación de transporte para Android con Kotlin. Aprenderás a conectar la aplicación con servicios web, a corregir errores y a publicar versiones de prueba. Trabajo híbrido: tres días en la oficina de Lima.',
     'HYBRID', 'Lima', 25, now() - interval '9 days 6 hours', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000007', 'c0000000-0000-4000-8000-000000000003',
     'Practicante de Datos con SQL',
     'Consulta y limpia datos con SQL y Python para armar reportes que usan otras áreas.',
     'Escribirás consultas SQL sobre una base PostgreSQL, limpiarás datos con Python y ayudarás a preparar reportes para clientes. Aprenderás a revisar la calidad de los datos y a explicar tus resultados con claridad. El trabajo es híbrido, con dos días en nuestra oficina de Lima.',
     'HYBRID', 'Lima', 20, now() - interval '5 days 2 hours', 'OPEN', true),
    ('d0000000-0000-4000-8000-000000000008', 'c0000000-0000-4000-8000-000000000003',
     'Practicante Python y automatización',
     'Automatiza tareas repetitivas con scripts de Python y apoya la preparación de datos.',
     'Crearás scripts en Python que descargan, ordenan y revisan archivos de datos, y aprenderás a ejecutarlos de forma automática en servidores Linux. Es un puesto remoto con pocas horas semanales, pensado para combinarlo con tus clases.',
     'REMOTE', NULL, 15, now() - interval '8 days 3 hours', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000009', 'c0000000-0000-4000-8000-000000000007',
     'Practicante Full Stack Node.js',
     'Programa pantallas y servicios con JavaScript y Node.js en un sistema de gestión para tiendas.',
     'Participarás en todo el recorrido de una función: la pantalla, el servicio con Node.js y la consulta a la base de datos. Aprenderás a organizar tu trabajo en tareas pequeñas y a revisar el código con el equipo. El trabajo es presencial en Lima.',
     'ONSITE', 'Lima', 30, now() - interval '10 days 1 hour', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000010', 'c0000000-0000-4000-8000-000000000008',
     'Practicante .NET',
     'Desarrolla funciones de un sistema administrativo con C# y .NET.',
     'Te unirás al equipo que mantiene un sistema administrativo hecho con C# y .NET. Aprenderás a agregar funciones, a escribir consultas SQL y a trabajar con Scrum en ciclos de dos semanas. El trabajo es presencial en Arequipa.',
     'ONSITE', 'Arequipa', 30, now() - interval '12 days 5 hours', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000011', 'c0000000-0000-4000-8000-000000000008',
     'Practicante PHP y Laravel',
     'Apoya la programación de sistemas web con PHP y Laravel para clientes de la región.',
     'Colaborarás en sistemas web hechos con PHP y Laravel: formularios, reportes y paneles de administración. Aprenderás a organizar el código, a consultar la base de datos y a usar Git en equipo. Trabajo híbrido, con presencia en Arequipa dos días por semana.',
     'HYBRID', 'Arequipa', 20, now() - interval '14 days', 'OPEN', false),
    ('d0000000-0000-4000-8000-000000000012', 'c0000000-0000-4000-8000-000000000004',
     'Practicante DevOps junior',
     'Aprende a automatizar despliegues y a mantener servidores con Linux y Docker.',
     'Apoyarás al equipo que prepara y mantiene los entornos donde corren las aplicaciones: servidores Linux, contenedores con Docker y scripts de despliegue. Aprenderás a vigilar que todo funcione y a resolver problemas con calma. El trabajo es remoto, con horarios flexibles.',
     'REMOTE', NULL, 20, now() - interval '3 hours', 'OPEN', true);

-- ---------------------------------------------------------------------------
-- Requisitos (3 obligatorios y 2 deseables por vacante). Los deseables piden nivel 1.
-- "Practicante Backend Java" lleva los requisitos del ejemplo de RN-01.
-- ---------------------------------------------------------------------------
INSERT INTO vacancy_requirement (vacancy_id, skill_id, kind, min_level)
SELECT r.vacancy_id, s.id, r.kind, r.min_level
FROM (VALUES
    -- 01 Practicante Backend Java
    ('d0000000-0000-4000-8000-000000000001'::uuid, 'java',        'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000001'::uuid, 'spring-boot', 'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000001'::uuid, 'sql',         'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000001'::uuid, 'git',         'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000001'::uuid, 'docker',      'DESIRABLE', 1),
    -- 02 Practicante Frontend
    ('d0000000-0000-4000-8000-000000000002'::uuid, 'javascript',  'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000002'::uuid, 'html',        'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000002'::uuid, 'css',         'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000002'::uuid, 'react',       'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000002'::uuid, 'git',         'DESIRABLE', 1),
    -- 03 Practicante React y TypeScript
    ('d0000000-0000-4000-8000-000000000003'::uuid, 'react',       'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000003'::uuid, 'typescript',  'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000003'::uuid, 'git',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000003'::uuid, 'testing',     'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000003'::uuid, 'rest-apis',   'DESIRABLE', 1),
    -- 04 Practicante de Calidad
    ('d0000000-0000-4000-8000-000000000004'::uuid, 'testing',     'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000004'::uuid, 'postman',     'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000004'::uuid, 'git',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000004'::uuid, 'selenium',    'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000004'::uuid, 'sql',         'DESIRABLE', 1),
    -- 05 Practicante iOS
    ('d0000000-0000-4000-8000-000000000005'::uuid, 'swift',       'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000005'::uuid, 'rest-apis',   'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000005'::uuid, 'git',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000005'::uuid, 'testing',     'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000005'::uuid, 'scrum',       'DESIRABLE', 1),
    -- 06 Practicante Android
    ('d0000000-0000-4000-8000-000000000006'::uuid, 'kotlin',      'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000006'::uuid, 'android',     'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000006'::uuid, 'git',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000006'::uuid, 'rest-apis',   'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000006'::uuid, 'testing',     'DESIRABLE', 1),
    -- 07 Practicante de Datos con SQL
    ('d0000000-0000-4000-8000-000000000007'::uuid, 'sql',         'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000007'::uuid, 'python',      'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000007'::uuid, 'postgresql',  'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000007'::uuid, 'git',         'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000007'::uuid, 'linux',       'DESIRABLE', 1),
    -- 08 Practicante Python y automatización
    ('d0000000-0000-4000-8000-000000000008'::uuid, 'python',      'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000008'::uuid, 'git',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000008'::uuid, 'linux',       'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000008'::uuid, 'docker',      'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000008'::uuid, 'sql',         'DESIRABLE', 1),
    -- 09 Practicante Full Stack Node.js
    ('d0000000-0000-4000-8000-000000000009'::uuid, 'javascript',  'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000009'::uuid, 'node-js',     'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000009'::uuid, 'sql',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000009'::uuid, 'mongodb',     'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000009'::uuid, 'docker',      'DESIRABLE', 1),
    -- 10 Practicante .NET
    ('d0000000-0000-4000-8000-000000000010'::uuid, 'csharp',      'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000010'::uuid, 'dotnet',      'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000010'::uuid, 'sql',         'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000010'::uuid, 'git',         'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000010'::uuid, 'scrum',       'DESIRABLE', 1),
    -- 11 Practicante PHP y Laravel
    ('d0000000-0000-4000-8000-000000000011'::uuid, 'php',         'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000011'::uuid, 'laravel',     'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000011'::uuid, 'sql',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000011'::uuid, 'javascript',  'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000011'::uuid, 'git',         'DESIRABLE', 1),
    -- 12 Practicante DevOps junior
    ('d0000000-0000-4000-8000-000000000012'::uuid, 'linux',       'REQUIRED',  2),
    ('d0000000-0000-4000-8000-000000000012'::uuid, 'docker',      'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000012'::uuid, 'git',         'REQUIRED',  1),
    ('d0000000-0000-4000-8000-000000000012'::uuid, 'aws',         'DESIRABLE', 1),
    ('d0000000-0000-4000-8000-000000000012'::uuid, 'python',      'DESIRABLE', 1)
) AS r (vacancy_id, slug, kind, min_level)
JOIN skill s ON s.slug = r.slug;

-- Verificación: si un identificador de habilidad estuviera mal escrito, el JOIN lo descartaría sin avisar.
DO $$
DECLARE
    loaded integer;
BEGIN
    SELECT count(*) INTO loaded FROM vacancy_requirement
    WHERE vacancy_id::text LIKE 'd0000000-0000-4000-8000-%';
    IF loaded <> 60 THEN
        RAISE EXCEPTION 'La semilla de ejemplo esperaba 60 requisitos y cargó %', loaded;
    END IF;
END
$$;
