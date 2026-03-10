-- 泛化 frequency_states 表：支持 typeId 字符串 + subject_id 组合键
-- 将 NotificationType 枚举值（UPPER_SNAKE_CASE）映射为 snake_case typeId

-- 1. 创建新表
CREATE TABLE frequency_states_new (
    notification_type        TEXT NOT NULL,
    subject_id               TEXT NOT NULL DEFAULT '',
    state                    TEXT NOT NULL DEFAULT 'NORMAL',
    consecutive_ignore_count INTEGER NOT NULL DEFAULT 0,
    last_notified_at         TEXT,
    created_at               TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at               TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (notification_type, subject_id)
);

-- 2. 迁移数据：枚举名 LOWER() 映射为 snake_case typeId，subject_id 设为空字符串
INSERT INTO frequency_states_new
    (notification_type, subject_id, state, consecutive_ignore_count,
     last_notified_at, created_at, updated_at)
SELECT
    LOWER(notification_type),
    '',
    state,
    consecutive_ignore_count,
    last_notified_at,
    created_at,
    updated_at
FROM frequency_states;

-- 3. 替换旧表
DROP TABLE frequency_states;
ALTER TABLE frequency_states_new RENAME TO frequency_states;

-- 4. 更新 proactive_notifications 表中已有数据的 notification_type 为 snake_case
UPDATE proactive_notifications SET notification_type = LOWER(notification_type);
