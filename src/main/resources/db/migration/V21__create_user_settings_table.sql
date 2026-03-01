-- V21: 创建用户设置表
-- 用于持久化用户设置（主题、语言、LLM Provider 等）

CREATE TABLE IF NOT EXISTS user_settings (
    id                      TEXT PRIMARY KEY,
    theme                   TEXT NOT NULL DEFAULT 'system',
    language                 TEXT NOT NULL DEFAULT 'zh-CN',
    llm_provider            TEXT NOT NULL DEFAULT 'ollama-qwen2.5',
    enable_streaming         INTEGER NOT NULL DEFAULT 1,
    enable_function_call     INTEGER NOT NULL DEFAULT 1,
    enable_knowledge_base    INTEGER NOT NULL DEFAULT 1,
    enable_tool_call         INTEGER NOT NULL DEFAULT 1,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

-- 插入默认设置（如果不存在）
INSERT OR IGNORE INTO user_settings (
    id, theme, language, llm_provider,
    enable_streaming, enable_function_call, enable_knowledge_base, enable_tool_call,
    created_at, updated_at
) VALUES (
    'default',
    'system',
    'zh-CN',
    'ollama-qwen2.5',
    1, 1, 1, 1,
    datetime('now'),
    datetime('now')
);
