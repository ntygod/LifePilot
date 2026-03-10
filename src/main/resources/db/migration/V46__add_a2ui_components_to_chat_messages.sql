-- V46: 为 chat_messages 表添加 A2UI 组件树 JSON 列
ALTER TABLE chat_messages ADD COLUMN a2ui_components_json TEXT;
