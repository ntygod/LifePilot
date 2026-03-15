-- 用户设置表新增 Reranker 配置 JSON 列
ALTER TABLE user_settings ADD COLUMN reranker_config_json TEXT DEFAULT '{}';
