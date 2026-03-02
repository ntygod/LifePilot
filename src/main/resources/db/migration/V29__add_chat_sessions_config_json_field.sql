-- V29: 添加 chat_sessions 表的 config_json 字段
-- 用于存储会话配置（模型ID、温度参数、最大Tokens等）

ALTER TABLE chat_sessions ADD COLUMN config_json TEXT DEFAULT '{}';
