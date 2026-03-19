-- 渠道配置列：存储飞书/企微/钉钉等渠道凭据（JSON 格式）
ALTER TABLE user_settings ADD COLUMN channel_config_json TEXT DEFAULT '{}';
