-- ============================================================
-- V2 — 下架文档工作区
-- 删除 session_documents / document_versions 两张表及其索引、外键
-- 顺序：先删带外键的子表 document_versions，再删父表 session_documents
-- 使用 IF EXISTS 保证幂等：干净库与已有数据库均可成功执行
-- 关联 spec：.kiro/specs/remove-document-workspace
-- ============================================================

-- 清理 session_artifacts 中可能指向已删除 transcript entry 的悬空 FK
-- （source_entry_id 列 ON DELETE SET NULL，但历史数据可能残留无效引用）
UPDATE session_artifacts SET source_entry_id = NULL
WHERE source_entry_id IS NOT NULL
  AND source_entry_id NOT IN (SELECT id FROM session_transcript_entries);

DROP TABLE IF EXISTS document_versions;
DROP TABLE IF EXISTS session_documents;
