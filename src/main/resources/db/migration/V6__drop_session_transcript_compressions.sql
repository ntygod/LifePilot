-- 删除 L2 情景记忆 per-entry 压缩投影表。
--
-- 背景：原 CompressionService 滑动窗口压缩为无生产调用方的死代码，已在
-- context-management-optimization 特性中删除；该表的唯一写入路径
-- (EpisodicMemory.compress/upsertCompressionProjection) 与读取 JOIN 一并移除。
-- 对话压缩统一收敛到会话级 CompactionEngine（compaction_summary transcript 条目），
-- 不再使用本表。按开发期约定删除冗余表（不改写已合并的 V1）。
--
-- @author zsg
-- @since 2026-06-12

DROP TABLE IF EXISTS session_transcript_compressions;
