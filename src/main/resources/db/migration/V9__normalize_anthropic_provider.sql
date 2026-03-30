UPDATE model_services
SET
    provider_type = 'ANTHROPIC',
    api_url = CASE
        WHEN api_url LIKE '%/v1/' THEN substr(api_url, 1, length(api_url) - 4)
        WHEN api_url LIKE '%/v1' THEN substr(api_url, 1, length(api_url) - 3)
        ELSE api_url
    END,
    updated_at = datetime('now')
WHERE provider_type = 'OPENAI_COMPATIBLE'
  AND (
      api_url LIKE 'https://api.anthropic.com%'
      OR model_name LIKE 'claude-%'
  );
