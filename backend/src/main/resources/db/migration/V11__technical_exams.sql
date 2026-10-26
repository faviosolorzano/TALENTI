-- Spec 014. Tablas de exam; sin datos de estudiantes en los conjuntos de preguntas.
CREATE TABLE exam_set (
    id uuid PRIMARY KEY,
    vacancy_id uuid NOT NULL REFERENCES vacancy(id) ON DELETE CASCADE,
    revision integer NOT NULL CHECK (revision > 0),
    status varchar(15) NOT NULL CHECK (status IN ('GENERATING','VERIFYING','READY','FAILED','SUPERSEDED')),
    blueprint jsonb NOT NULL,
    source varchar(5) CHECK (source IN ('AI','BANK','MIXED')),
    model varchar(60), prompt_version varchar(60), verifier_version varchar(60), bank_version varchar(60),
    failure_reason varchar(60), created_at timestamptz NOT NULL, ready_at timestamptz,
    UNIQUE(vacancy_id,revision)
);
CREATE UNIQUE INDEX exam_set_current_vacancy ON exam_set(vacancy_id)
    WHERE status NOT IN ('FAILED','SUPERSEDED');

CREATE TABLE exam_version (
    id uuid PRIMARY KEY, set_id uuid NOT NULL REFERENCES exam_set(id) ON DELETE CASCADE,
    label varchar(1) NOT NULL CHECK(label IN ('A','B','C')),
    verification_report jsonb NOT NULL DEFAULT '{}', locked boolean NOT NULL DEFAULT false,
    UNIQUE(set_id,label)
);
CREATE TABLE exam_question (
    id uuid PRIMARY KEY, version_id uuid NOT NULL REFERENCES exam_version(id) ON DELETE CASCADE,
    slot smallint NOT NULL CHECK(slot BETWEEN 1 AND 10),
    type varchar(6) NOT NULL CHECK(type IN ('CHOICE','OPEN','CODE')),
    skill_slug varchar(60) NOT NULL, difficulty smallint NOT NULL CHECK(difficulty BETWEEN 1 AND 3),
    points numeric(5,2) NOT NULL CHECK(points > 0 AND points <= 100),
    prompt varchar(2000) NOT NULL, content jsonb NOT NULL,
    source varchar(4) NOT NULL CHECK(source IN ('AI','BANK')), UNIQUE(version_id,slot)
);
CREATE TABLE exam_attempt (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL UNIQUE REFERENCES application(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    version_id uuid NOT NULL REFERENCES exam_version(id) ON DELETE RESTRICT,
    shuffle_seed bigint NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('NOT_STARTED','IN_PROGRESS','SUBMITTED','TIMED_OUT','CLOSED_BY_RULE','CLOSED_WITHDRAWN')),
    grading_status varchar(12) NOT NULL DEFAULT 'NOT_STARTED' CHECK(grading_status IN ('NOT_STARTED','IN_PROGRESS','PROVISIONAL','DONE')),
    notice_version varchar(30) NOT NULL, notice_accepted_at timestamptz NOT NULL,
    started_at timestamptz, deadline_at timestamptz, ended_at timestamptz, closed_reason varchar(30),
    screen_left_count integer NOT NULL DEFAULT 0 CHECK(screen_left_count >= 0),
    score_100 numeric(5,2) CHECK(score_100 BETWEEN 0 AND 100),
    score_20 numeric(4,2) CHECK(score_20 BETWEEN 0 AND 20),
    scoring_version varchar(40) NOT NULL, created_at timestamptz NOT NULL
);
CREATE INDEX exam_attempt_deadline ON exam_attempt(deadline_at) WHERE status = 'IN_PROGRESS';
CREATE INDEX exam_attempt_owner ON exam_attempt(user_id,created_at DESC);
CREATE TABLE exam_answer (
    id uuid PRIMARY KEY, attempt_id uuid NOT NULL REFERENCES exam_attempt(id) ON DELETE CASCADE,
    question_id uuid NOT NULL REFERENCES exam_question(id) ON DELETE RESTRICT,
    choice_id varchar(60), text varchar(4000), code varchar(10000) CHECK(octet_length(code) <= 10000),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision >= 0),
    run_count integer NOT NULL DEFAULT 0 CHECK(run_count >= 0),
    grade_status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(grade_status IN ('PENDING','DONE','UNAVAILABLE','UNANSWERED')),
    points numeric(5,2), grade_detail jsonb NOT NULL DEFAULT '{}',
    grading_attempts integer NOT NULL DEFAULT 0, next_retry_at timestamptz,
    updated_at timestamptz NOT NULL,
    CHECK(num_nonnulls(choice_id,text,code) <= 1), UNIQUE(attempt_id,question_id)
);
CREATE INDEX exam_answer_retry ON exam_answer(next_retry_at) WHERE grade_status = 'PENDING';
CREATE TABLE exam_event (
    id uuid PRIMARY KEY, attempt_id uuid NOT NULL REFERENCES exam_attempt(id) ON DELETE CASCADE,
    kind varchar(30) NOT NULL CHECK(kind IN ('SCREEN_LEFT','SCREEN_RETURNED','FOCUS_LOST','COPY_ATTEMPT',
        'PASTE_ATTEMPT','GAZE_AWAY','CONNECTION_LOST','CONNECTION_RESTORED','WARNING_ISSUED',
        'CLOSED_BY_RULE','STARTED','SUBMITTED','TIMED_OUT','WITHDRAWN')),
    client_event_id uuid, client_occurred_at timestamptz, occurred_at timestamptz NOT NULL,
    data jsonb NOT NULL DEFAULT '{}', UNIQUE(attempt_id,client_event_id)
);
CREATE INDEX exam_event_attempt ON exam_event(attempt_id,occurred_at,id);

-- Un intento fija para siempre la revisión y las preguntas usadas.
CREATE FUNCTION exam_protect_locked_question() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM exam_version WHERE id = OLD.version_id AND locked) THEN
        RAISE EXCEPTION 'exam-version-locked';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exam_locked_question BEFORE UPDATE OR DELETE ON exam_question
    FOR EACH ROW EXECUTE FUNCTION exam_protect_locked_question();
CREATE FUNCTION exam_protect_locked_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.locked AND (NOT NEW.locked OR NEW.set_id <> OLD.set_id OR NEW.label <> OLD.label
        OR NEW.verification_report IS DISTINCT FROM OLD.verification_report) THEN
        RAISE EXCEPTION 'exam-version-locked';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exam_locked_version BEFORE UPDATE ON exam_version
    FOR EACH ROW EXECUTE FUNCTION exam_protect_locked_version();

ALTER TABLE ai_usage_event DROP CONSTRAINT ck_ai_usage_event_function;
ALTER TABLE ai_usage_event ADD CONSTRAINT ck_ai_usage_event_function CHECK(function IN
    ('CV_READ','PRACTICE_SESSION','PRACTICE_FEEDBACK','EXPLANATION','STT','TTS','DORA_DIALOG',
     'INTERVIEW_SESSION','INTERVIEW_EVALUATION','EXAM_GENERATION','EXAM_GRADING'));
