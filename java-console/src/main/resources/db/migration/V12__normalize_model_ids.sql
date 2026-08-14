-- Normalize legacy/demo model identifiers to the runtime capability catalog.
UPDATE agent_definition SET model = CASE model
    WHEN 'deepseek-v4-pro' THEN 'deepseek-reasoner'
    WHEN 'deepseek-v4-flash' THEN 'deepseek-chat'
    WHEN 'claude-opus-4-8' THEN 'claude-sonnet-4-5'
    WHEN 'claude-sonnet-5' THEN 'claude-sonnet-4-5'
    WHEN 'qwen-max' THEN 'qwen-plus'
    WHEN 'kimi-k2.6' THEN 'moonshot-v1-32k'
    WHEN 'kimi-k2.7-code-hs' THEN 'moonshot-v1-32k'
    WHEN 'glm-4.7' THEN 'glm-4-plus'
    WHEN 'glm-4.7-flash' THEN 'glm-4-plus'
    WHEN 'mistral-large' THEN 'mistral-large-latest'
    WHEN 'mistral-small' THEN 'mistral-large-latest'
    ELSE model
END
WHERE model IN (
    'deepseek-v4-pro', 'deepseek-v4-flash', 'claude-opus-4-8', 'claude-sonnet-5',
    'qwen-max', 'kimi-k2.6', 'kimi-k2.7-code-hs', 'glm-4.7', 'glm-4.7-flash',
    'mistral-large', 'mistral-small'
);
