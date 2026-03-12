-- V49: 为用户设置增加按场景默认 Provider 配置

ALTER TABLE user_settings
    ADD COLUMN scene_providers TEXT NOT NULL DEFAULT '{}';

UPDATE user_settings
SET scene_providers = '{}'
WHERE scene_providers IS NULL
   OR TRIM(scene_providers) = '';
