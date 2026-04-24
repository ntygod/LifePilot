-- V18：给 proactive_reminder_feedback 加 insight_entity_id 列，支持 notification → insight 反查
-- 目的：Task 25（S14 解法）— 提醒被标 "无用" 时，需要找到生成该提醒所依据的
-- L3 proactive_insight_* 实体，对其 importanceScore 做 -0.5 惩罚；3 次累计即触发
-- NegativeFeedbackListener 的阈值判定把实体转 SUPERSEDED。
--
-- 为什么选 proactive_reminder_feedback 表而非 notification_history：
--   1. 反馈是在用户提交"无用"时才发生，insight 归属关系一并持久化最自然
--   2. 老数据 / 老提醒路径不写 insight 关联时 NULL 即可，反查无结果直接跳过
--   3. 复用现有 FOREIGN KEY notification_history(id) 级联，避免孤儿数据
--
-- @author zsg
-- @since 2026-04-23

ALTER TABLE proactive_reminder_feedback ADD COLUMN insight_entity_id TEXT;
CREATE INDEX idx_proactive_reminder_feedback_insight
    ON proactive_reminder_feedback(insight_entity_id);
