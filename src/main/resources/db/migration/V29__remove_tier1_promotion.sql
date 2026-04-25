-- 删除 Tier 1 晋升机制相关表
-- 知微为单机本地部署，没有"管理员审批"角色，PENDING 永远没人 APPROVE，
-- 整套晋升 / 降级机制为死代码。Tier 1 工具改为完全由 application.yml 的
-- lifepilot.tool.tier1.pinned 列表手工维护。

DROP INDEX IF EXISTS idx_tier1_advisory_status;
DROP INDEX IF EXISTS idx_tier1_advisory_tool;
DROP TABLE IF EXISTS tier1_advisory;

DROP INDEX IF EXISTS idx_tool_usage_stats_date;
DROP TABLE IF EXISTS tool_usage_stats;

DROP INDEX IF EXISTS idx_daily_active_sessions_date;
DROP TABLE IF EXISTS daily_active_sessions;
