-- ============================================================
-- V4: 想法成熟度演化（thought-maturity-evolution）
-- 为 initiative_thoughts 增加 last_reinforced_at —— 成熟度停滞衰减的计时基准。
-- 独立 ALTER 迁移：V3 在已有 dev 库可能已应用，避免改写 V3 触发 checksum 不一致。
-- ============================================================

ALTER TABLE initiative_thoughts ADD COLUMN last_reinforced_at TEXT;
