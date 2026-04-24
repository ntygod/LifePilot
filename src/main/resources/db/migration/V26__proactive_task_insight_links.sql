-- V17：主动任务 → L3 insight 关联表
-- @author zsg
-- @since 2026-04-23
--
-- 背景：Task 13（S10 前置）需要在 ProactiveEngine.markGoalFulfilled
-- 发 ProactiveTaskCancelled 事件时，携带"该任务产出的 L3 insight
-- 实体 id"列表供 ProactiveTaskCancelListener 级联取消。
--
-- 现状：主动任务（以 GOAL 实体 id 为 taskId）与 ProactiveMemoryBridge
-- .syncInsightToL3 产出的 PREFERENCE insight 实体之间没有关联字段，
-- 只能通过 provenance.source='proactive-engine' 粗粒度追溯整个引擎的
-- 全部产出，无法区分"该 goal 下的 insight"。
--
-- 方案：新建轻量关联表 proactive_task_insight_links，在
-- syncInsightToL3(taskId, ...) 时同步写入一条 (task_id, entity_id) 行；
-- markGoalFulfilled 时按 task_id 查询所有关联实体 id 作为事件 payload。
CREATE TABLE IF NOT EXISTS proactive_task_insight_links (
    task_id    TEXT NOT NULL,
    entity_id  TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (task_id, entity_id),
    FOREIGN KEY (entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_task_insight_task ON proactive_task_insight_links(task_id);
CREATE INDEX idx_proactive_task_insight_entity ON proactive_task_insight_links(entity_id);
