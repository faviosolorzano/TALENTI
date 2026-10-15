-- Cuenta de demostración (T-10): un estudiante con el perfil lleno y cercano a la vacante "Practicante Backend Java",
-- para que la presentación no dependa de crear una cuenta. TODO es inventado: ninguna persona real.
-- Solo se carga con la semilla de ejemplo activa (talenti.demo-data.enabled=true), igual que V1000.
-- Correo: demo@example.test. Contraseña de demostración: Demo-Talenti-2026 (documentada en el README; no es un secreto).
-- Es idempotente: si el correo ya existe no se toca nada.

INSERT INTO app_user (id, email, password_hash, role)
VALUES ('a0000000-0000-4000-8000-000000000001', 'demo@example.test',
        '$2a$12$rG9OB2eh.naVcjMQZ3hvbOnN/c4r4K2SQKczhDn/WMMd5/MxoB4.W', 'STUDENT')
ON CONFLICT (email) DO NOTHING;

-- Aceptación de los documentos legales vigentes (v1), para no caer en /aceptar-documentos.
INSERT INTO consent_record (user_id, purpose, consent_version, accepted_at)
SELECT u.id, p.purpose, 'v1', now()
FROM app_user u CROSS JOIN (VALUES ('TERMS'), ('ACCOUNT_DATA')) AS p(purpose)
WHERE u.id = 'a0000000-0000-4000-8000-000000000001';

INSERT INTO student_profile (user_id, first_names, last_names, about, career, institute, cycle, hours_per_week,
                             accepted_modalities, location, contact_email, phone)
SELECT u.id, 'Camila', 'Rojas Demo',
       'Estudiante de Desarrollo de Software con ganas de aprender. Me gusta programar servicios web con Java y practicar consultas SQL.',
       'Desarrollo de Software', 'Instituto Demo', 5, 20, ARRAY['HYBRID', 'REMOTE'], 'Lima',
       'camila.demo@example.test', '900000001'
FROM app_user u WHERE u.id = 'a0000000-0000-4000-8000-000000000001'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO student_skill (user_id, skill_id, level, evidence)
SELECT u.id, s.id, v.level, 'DECLARED'
FROM app_user u
JOIN (VALUES ('java', 3), ('spring-boot', 2), ('sql', 2), ('git', 2), ('docker', 1)) AS v(slug, level) ON true
JOIN skill s ON s.slug = v.slug
WHERE u.id = 'a0000000-0000-4000-8000-000000000001'
ON CONFLICT (user_id, skill_id) DO NOTHING;

INSERT INTO student_project (user_id, name, description, technologies)
SELECT u.id, v.name, v.description, v.technologies
FROM app_user u
JOIN (VALUES
    ('Tienda en línea de ejemplo', 'Servicio web de una tienda con catálogo, carrito y pedidos, hecho para el curso de servicios web.',
     ARRAY['Java', 'Spring Boot', 'PostgreSQL']),
    ('Agenda de citas', 'Aplicación para reservar citas con recordatorios, con consultas SQL y pruebas básicas.',
     ARRAY['Java', 'SQL', 'Git'])
) AS v(name, description, technologies) ON true
WHERE u.id = 'a0000000-0000-4000-8000-000000000001'
  AND NOT EXISTS (SELECT 1 FROM student_project p WHERE p.user_id = u.id);
