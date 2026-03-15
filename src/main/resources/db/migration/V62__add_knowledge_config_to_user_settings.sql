-- 用户设置表新增知识库全局配置 JSON 列
ALTER TABLE user_settings ADD COLUMN knowledge_config_json TEXT DEFAULT '{}';
