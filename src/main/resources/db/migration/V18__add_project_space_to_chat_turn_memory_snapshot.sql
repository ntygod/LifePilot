-- Plan 1 Task 15: 对话级记忆写入需按项目隔离。
-- snapshot 加 project_space_id：ChatTurnService.persistTurnMemorySnapshot 按 ChatSession.projectId
-- 反查 ProjectContext，ISOLATED 时填入；SHARED / 主账户对话留 NULL。
-- 下游 RealtimeExtractor / 经验写入路径从 snapshot 拿该字段决定 writeContext.spaceId。
-- 不加 FK to memory_spaces：SQLite 的 ALTER TABLE ADD COLUMN 不支持同时声明外键约束，
-- 后续若需强制引用完整性需走表重建（对现有数据影响较大），此处保持与应用层校验一致。
ALTER TABLE chat_turn_memory_snapshots ADD COLUMN project_space_id TEXT;
