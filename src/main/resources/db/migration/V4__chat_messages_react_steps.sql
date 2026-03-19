-- 为 chat_messages 表添加 react_steps_json 列，存储 ReAct 步骤序列化 JSON
ALTER TABLE chat_messages ADD COLUMN react_steps_json TEXT;
