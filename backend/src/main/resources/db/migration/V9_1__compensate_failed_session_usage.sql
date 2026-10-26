-- 012-29: compensación solo por inserciones; no borra consumo ni costo ya producido.
-- 9.1 se aplica después de DORA (V9), antes de GitHub (V10) y del examen (V11).
ALTER TABLE ai_usage_event ADD COLUMN reverses_read_id uuid;
CREATE UNIQUE INDEX ai_usage_event_one_reversal ON ai_usage_event (reverses_read_id)
    WHERE reverses_read_id IS NOT NULL;
ALTER TABLE ai_usage_event ADD CONSTRAINT ai_usage_event_session_reversal CHECK (
    reverses_read_id IS NULL OR (
        outcome = 'FAILED_PLATFORM' AND function IN ('PRACTICE_SESSION', 'INTERVIEW_SESSION')
        AND reverses_read_id <> read_id
    )
);

-- Repara también sesiones que fallaron antes de esta corrección.
INSERT INTO ai_usage_event (id, occurred_at, user_id, function, level, adapter, model, outcome,
                            input_size, output_size, read_id, estimated_cost_micros, reverses_read_id)
SELECT gen_random_uuid(), now(), u.user_id, u.function, u.level, u.adapter, NULL, 'FAILED_PLATFORM',
       0, 0, gen_random_uuid(), NULL, u.read_id
FROM ai_usage_event u JOIN practice_session s ON s.id = u.read_id
WHERE s.status = 'FAILED' AND u.outcome = 'SUCCESS'
  AND u.function IN ('PRACTICE_SESSION', 'INTERVIEW_SESSION')
ON CONFLICT DO NOTHING;
