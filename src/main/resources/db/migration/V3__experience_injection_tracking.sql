-- 经验注入追踪：为 memory_injection_records 表新增 entity_type 和 trace_id 列
ALTER TABLE memory_injection_records ADD COLUMN entity_type TEXT DEFAULT 'GENERAL';
ALTER TABLE memory_injection_records ADD COLUMN trace_id TEXT;
CREATE INDEX IF NOT EXISTS idx_injection_trace_type ON memory_injection_records(trace_id, entity_type);
