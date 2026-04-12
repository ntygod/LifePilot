-- 用户设置表新增「默认工作目录」字段
-- NULL 表示使用系统默认值（~/.zhiwei/workspace/）
ALTER TABLE user_settings ADD COLUMN default_workspace TEXT DEFAULT NULL;
