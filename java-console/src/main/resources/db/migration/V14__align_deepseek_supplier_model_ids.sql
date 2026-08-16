-- Align persisted DeepSeek model identifiers with the supplier catalog.
-- Historical token usage and traces remain unchanged for audit accuracy.

UPDATE agent_definition
SET model = CASE model
    WHEN 'deepseek-chat' THEN 'deepseek-v4-flash'
    WHEN 'deepseek-reasoner' THEN 'deepseek-v4-pro'
    ELSE model
END
WHERE model IN ('deepseek-chat', 'deepseek-reasoner');

UPDATE agent_version
SET config = jsonb_set(
        config,
        '{model}',
        to_jsonb(CASE config->>'model'
            WHEN 'deepseek-chat' THEN 'deepseek-v4-flash'
            WHEN 'deepseek-reasoner' THEN 'deepseek-v4-pro'
            ELSE config->>'model'
        END)
    )
WHERE config->>'model' IN ('deepseek-chat', 'deepseek-reasoner');

DELETE FROM model_endpoint legacy
USING model_endpoint current
WHERE legacy.tenant_id = current.tenant_id
  AND legacy.region = current.region
  AND ((legacy.model = 'deepseek-chat' AND current.model = 'deepseek-v4-flash')
    OR (legacy.model = 'deepseek-reasoner' AND current.model = 'deepseek-v4-pro'));

UPDATE model_endpoint
SET model = CASE model
        WHEN 'deepseek-chat' THEN 'deepseek-v4-flash'
        WHEN 'deepseek-reasoner' THEN 'deepseek-v4-pro'
        ELSE model
    END,
    status = 'unknown',
    circuit_state = 'closed',
    consecutive_failures = 0,
    last_error = NULL,
    recover_after = NULL,
    updated_at = NOW()
WHERE model IN ('deepseek-chat', 'deepseek-reasoner');
