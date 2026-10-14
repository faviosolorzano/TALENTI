-- Enmienda 2 de la spec 006 (006-27, 006-30): nombres y apellidos formales y datos de contacto.
-- display_name pasa a first_names sin perder datos. Ninguno de estos datos entra en el avance, la compatibilidad
-- ni sale hacia una IA, ni se registra en logs.
ALTER TABLE student_profile RENAME COLUMN display_name TO first_names;
ALTER TABLE student_profile DROP CONSTRAINT ck_student_profile_display_name;
ALTER TABLE student_profile ALTER COLUMN first_names TYPE varchar(60);
ALTER TABLE student_profile
    ADD COLUMN last_names    varchar(60),
    ADD COLUMN contact_email varchar(254),
    ADD COLUMN phone         varchar(9);
ALTER TABLE student_profile
    ADD CONSTRAINT ck_student_profile_first_names CHECK (first_names IS NULL OR btrim(first_names) <> ''),
    ADD CONSTRAINT ck_student_profile_last_names CHECK (last_names IS NULL OR btrim(last_names) <> ''),
    ADD CONSTRAINT ck_student_profile_contact_email CHECK (contact_email IS NULL OR btrim(contact_email) <> ''),
    ADD CONSTRAINT ck_student_profile_phone CHECK (phone IS NULL OR phone ~ '^9[0-9]{8}$');
