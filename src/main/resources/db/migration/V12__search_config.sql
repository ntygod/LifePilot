ALTER TABLE user_settings
    ADD COLUMN search_config_json TEXT DEFAULT '{}';

UPDATE user_settings
SET search_config_json = '{}'
WHERE search_config_json IS NULL
   OR TRIM(search_config_json) = '';
