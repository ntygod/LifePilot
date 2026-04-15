-- 渠道平台用户/会话 ID 映射表
-- 入站消息自动写入，通知/审批发送时查询
CREATE TABLE IF NOT EXISTS channel_user_mappings (
    instance_id         TEXT NOT NULL,
    platform_user_id    TEXT NOT NULL,
    platform_session_id TEXT,
    last_seen_at        TEXT NOT NULL,
    PRIMARY KEY (instance_id)
);
