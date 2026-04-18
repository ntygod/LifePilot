-- 用户设置表新增「外部 CLI Bash 依赖」字段
-- 用于给 claude / codex 等需要 Unix bash 的 CLI 注入 CLAUDE_CODE_GIT_BASH_PATH 环境变量
-- NULL 表示未配置，知微不注入 env，CLI 自行处理失败
ALTER TABLE user_settings ADD COLUMN external_cli_bash_path TEXT DEFAULT NULL;
