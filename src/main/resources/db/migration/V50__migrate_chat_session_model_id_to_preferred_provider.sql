-- V50: normalize legacy session config key modelId to preferredProvider

UPDATE chat_sessions
SET config_json = CASE
    WHEN json_extract(COALESCE(NULLIF(TRIM(config_json), ''), '{}'), '$.preferredProvider') IS NULL
        THEN json_remove(
            json_set(
                COALESCE(NULLIF(TRIM(config_json), ''), '{}'),
                '$.preferredProvider',
                json_extract(COALESCE(NULLIF(TRIM(config_json), ''), '{}'), '$.modelId')
            ),
            '$.modelId'
        )
    ELSE json_remove(COALESCE(NULLIF(TRIM(config_json), ''), '{}'), '$.modelId')
END
WHERE json_valid(COALESCE(NULLIF(TRIM(config_json), ''), '{}')) = 1
  AND json_extract(COALESCE(NULLIF(TRIM(config_json), ''), '{}'), '$.modelId') IS NOT NULL;
